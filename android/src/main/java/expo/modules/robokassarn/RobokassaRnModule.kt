package expo.modules.robokassarn

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcelable
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.Serializable
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RobokassaRnModule : Module() {
  @Volatile
  private var pendingPaymentContinuation: CancellableContinuation<RobokassaPaymentResult>? = null

  private val paymentLock = Any()

  override fun definition() = ModuleDefinition {
    Name("RobokassaRn")

    Function("isRobokassaSdkAvailable") {
      isClassAvailable(ROBOKASSA_ACTIVITY_CLASS) && isClassAvailable(PAYMENT_PARAMS_CLASS)
    }

    (AsyncFunction("startPaymentAsync") Coroutine { options: RobokassaPaymentOptions ->
      val activity = appContext.currentActivity ?: throw MissingCurrentActivityException()
      launchPaymentAndAwaitResult(activity, options)
    }).runOnQueue(Queues.MAIN)

    OnActivityResult { _, payload ->
      if (payload.requestCode != ROBOKASSA_ACTIVITY_REQUEST_CODE) {
        return@OnActivityResult
      }
      completePendingPayment(mapActivityResult(payload.resultCode, payload.data))
    }

    OnDestroy {
      failPendingPayment(PaymentFlowInterruptedException())
    }
  }

  private suspend fun launchPaymentAndAwaitResult(
    activity: Activity,
    options: RobokassaPaymentOptions
  ): RobokassaPaymentResult = suspendCancellableCoroutine { continuation ->
    synchronized(paymentLock) {
      if (pendingPaymentContinuation != null) {
        continuation.resumeWithException(PaymentInProgressException())
        return@suspendCancellableCoroutine
      }

      pendingPaymentContinuation = continuation
    }

    continuation.invokeOnCancellation {
      clearPendingState()
    }

    try {
      val redirectUrl = resolveRedirectUrl(activity)
      val paymentParams = createPaymentParams(options, redirectUrl)
      launchPaymentActivity(activity, paymentParams, options.testMode)
    } catch (error: Throwable) {
      clearPendingState()
      val root = unwrapThrowable(error)
      continuation.resumeWithException(
        RobokassaSdkInvocationException(
          "Failed to start Robokassa payment flow: ${root.message ?: "unknown"}",
          root
        )
      )
    }
  }

  private fun createPaymentParams(options: RobokassaPaymentOptions, redirectUrl: String): Any {
    val paymentParamsClass = loadRequiredClass(PAYMENT_PARAMS_CLASS)
    val cultureValue = resolveCultureConstant(options.culture)

    instantiateWithNoArgAndSetters(paymentParamsClass, options, cultureValue, redirectUrl)?.let {
      applyExtraParams(it, options.extra)
      return it
    }
    instantiateWithConstructors(paymentParamsClass, options, cultureValue)?.let {
      setPropertyIfPresent(it, "redirectUrl", redirectUrl)
      applyExtraParams(it, options.extra)
      return it
    }

    throw UnsupportedSdkVersionException(
      "Unable to instantiate PaymentParams. Check SDK compatibility."
    )
  }

  @Suppress("DEPRECATION")
  private fun launchPaymentActivity(activity: Activity, paymentParams: Any, testMode: Boolean) {
    val activityClass = loadRequiredClass(ROBOKASSA_ACTIVITY_CLASS)
    if (!Activity::class.java.isAssignableFrom(activityClass)) {
      throw UnsupportedSdkVersionException("RobokassaActivity class is not an Activity.")
    }

    val parcelableParams = paymentParams as? Parcelable
      ?: throw UnsupportedSdkVersionException("PaymentParams must implement Parcelable.")

    @Suppress("UNCHECKED_CAST")
    val target = activityClass as Class<out Activity>

    val intent = Intent(activity, target).apply {
      putExtra(EXTRA_PARAMS, parcelableParams)
      putExtra(EXTRA_TEST_PARAMETERS, testMode)
      putExtra(EXTRA_ONLY_CHECK, false)
    }

    activity.startActivityForResult(intent, ROBOKASSA_ACTIVITY_REQUEST_CODE)
  }

  private fun mapActivityResult(resultCode: Int, data: Intent?): RobokassaPaymentResult {
    return when (resultCode) {
      Activity.RESULT_OK -> RobokassaPaymentResult(
        status = "success",
        invoiceId = data?.getIntExtra(EXTRA_INVOICE_ID, -1)?.takeIf { it >= 0 }
      )

      Activity.RESULT_FIRST_USER -> {
        val errorPayload = getSerializableExtraCompat(data, EXTRA_ERROR)
        RobokassaPaymentResult(
          status = "error",
          errorCode = extractErrorValue(errorPayload, listOf("getCode", "getErrorCode", "code", "errorCode")),
          errorDescription = data?.getStringExtra(EXTRA_ERROR_DESC)
            ?: extractErrorValue(errorPayload, listOf("getDescription", "getErrorDescription", "description", "message", "getMessage"))
            ?: "Payment failed"
        )
      }

      else -> RobokassaPaymentResult(status = "cancelled")
    }
  }

  private fun getSerializableExtraCompat(intent: Intent?, key: String): Any? {
    if (intent == null) return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getSerializableExtra(key, Serializable::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent.getSerializableExtra(key)
    }
  }

  private fun instantiateWithConstructors(
    paymentParamsClass: Class<*>,
    options: RobokassaPaymentOptions,
    cultureValue: Any?
  ): Any? {
    val constructors = paymentParamsClass.declaredConstructors
      .filter { it.parameterTypes.isNotEmpty() }
      .sortedByDescending { it.parameterTypes.size }

    constructors.forEach { constructor ->
      val arguments = constructor.parameterTypes.mapIndexed { index, parameterType ->
        buildArgumentForParameter(index, parameterType, options, cultureValue)
      }.toTypedArray()

      try {
        constructor.isAccessible = true
        return constructor.newInstance(*arguments)
      } catch (_: Throwable) {
        // Continue with next constructor candidate.
      }
    }

    return null
  }

  private fun instantiateWithNoArgAndSetters(
    paymentParamsClass: Class<*>,
    options: RobokassaPaymentOptions,
    cultureValue: Any?,
    redirectUrl: String
  ): Any? {
    val normalizedExtra = normalizeExtraParams(options.extra)
    val constructor = paymentParamsClass.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() } ?: return null

    val paymentParams = constructor.newInstance()
    invokeMethodIfPresent(paymentParams, "setCredentials", options.merchantLogin, options.password1, options.password2, redirectUrl)

    setPropertyIfPresent(paymentParams, "merchantLogin", options.merchantLogin)
    setPropertyIfPresent(paymentParams, "password1", options.password1)
    setPropertyIfPresent(paymentParams, "password2", options.password2)
    setPropertyIfPresent(paymentParams, "redirectUrl", redirectUrl)
    setPropertyIfPresent(paymentParams, "invoiceId", options.invoiceId)
    setPropertyIfPresent(paymentParams, "orderSum", options.orderSum)
    setPropertyIfPresent(paymentParams, "description", options.description)
    setPropertyIfPresent(paymentParams, "email", options.email)
    setPropertyIfPresent(paymentParams, "culture", cultureValue ?: options.culture.value)
    setPropertyIfPresent(paymentParams, "isRecurrent", options.isRecurrent)
    setPropertyIfPresent(paymentParams, "isHold", options.isHold)
    setPropertyIfPresent(paymentParams, "toolbarText", options.toolbarText)
    setPropertyIfPresent(paymentParams, "toolbarBgColor", options.toolbarBgColor)
    setPropertyIfPresent(paymentParams, "toolbarTextColor", options.toolbarTextColor)
    setPropertyIfPresent(paymentParams, "hasToolbar", options.hasToolbar)
    setPropertyIfPresent(paymentParams, "previousInvoiceId", options.previousInvoiceId)
    setPropertyIfPresent(paymentParams, "token", options.token)
    setPropertyIfPresent(paymentParams, "extra", normalizedExtra)
    setPropertyIfPresent(paymentParams, "extraParams", normalizedExtra)
    setPropertyIfPresent(paymentParams, "customParams", normalizedExtra)

    val orderParams = resolveNestedParams(
      owner = paymentParams,
      getterNames = listOf("getOrderParams", "getOrder"),
      propertyName = "order",
      className = ORDER_PARAMS_CLASS
    )
    if (orderParams != null) {
      setPropertyIfPresent(orderParams, "invoiceId", options.invoiceId)
      setPropertyIfPresent(orderParams, "orderSum", options.orderSum)
      setPropertyIfPresent(orderParams, "description", options.description)
      setPropertyIfPresent(orderParams, "isRecurrent", options.isRecurrent)
      setPropertyIfPresent(orderParams, "isHold", options.isHold)
      setPropertyIfPresent(orderParams, "previousInvoiceId", options.previousInvoiceId)
      setPropertyIfPresent(orderParams, "token", options.token)
      setPropertyIfPresent(orderParams, "extra", normalizedExtra)
      setPropertyIfPresent(orderParams, "extraParams", normalizedExtra)
      setPropertyIfPresent(orderParams, "customParams", normalizedExtra)
    }

    val customerParams = resolveNestedParams(
      owner = paymentParams,
      getterNames = listOf("getCustomerParams", "getCustomer"),
      propertyName = "customer",
      className = CUSTOMER_PARAMS_CLASS
    )
    if (customerParams != null) {
      setPropertyIfPresent(customerParams, "email", options.email)
      setPropertyIfPresent(customerParams, "culture", cultureValue ?: options.culture.value)
    }

    val viewParams = resolveNestedParams(
      owner = paymentParams,
      getterNames = listOf("getViewParams", "getView"),
      propertyName = "view",
      className = VIEW_PARAMS_CLASS
    )
    if (viewParams != null) {
      setPropertyIfPresent(viewParams, "toolbarText", options.toolbarText)
      setPropertyIfPresent(viewParams, "toolbarBgColor", options.toolbarBgColor)
      setPropertyIfPresent(viewParams, "toolbarTextColor", options.toolbarTextColor)
      setPropertyIfPresent(viewParams, "hasToolbar", options.hasToolbar)
    }

    return paymentParams
  }

  private fun buildArgumentForParameter(
    index: Int,
    parameterType: Class<*>,
    options: RobokassaPaymentOptions,
    cultureValue: Any?
  ): Any? {
    return when (index) {
      0 -> coerceValue(parameterType, options.merchantLogin)
      1 -> coerceValue(parameterType, options.password1)
      2 -> coerceValue(parameterType, options.password2)
      3 -> coerceValue(parameterType, options.invoiceId)
      4 -> coerceValue(parameterType, options.orderSum)
      5 -> coerceValue(parameterType, options.description)
      6 -> coerceValue(parameterType, options.email)
      7 -> {
        val fallbackCulture = if (parameterType == String::class.java) options.culture.value else options.culture.name
        coerceValue(parameterType, cultureValue ?: fallbackCulture)
      }

      8 -> coerceValue(parameterType, options.isRecurrent)
      9 -> coerceValue(parameterType, options.isHold)
      10 -> coerceValue(parameterType, options.toolbarText)
      11 -> coerceValue(parameterType, options.previousInvoiceId)
      12 -> coerceValue(parameterType, options.token)
      13 -> coerceValue(parameterType, normalizeExtraParams(options.extra))
      14 -> coerceValue(parameterType, options.toolbarBgColor)
      15 -> coerceValue(parameterType, options.toolbarTextColor)
      16 -> coerceValue(parameterType, options.hasToolbar)
      else -> defaultValueForType(parameterType)
    }
  }

  private fun resolveCultureConstant(culture: RobokassaCulture): Any? {
    val cultureClass = CULTURE_CLASS_CANDIDATES.firstNotNullOfOrNull { className ->
      loadOptionalClass(className)
    } ?: return null
    if (!cultureClass.isEnum) {
      return null
    }

    val requestedValue = culture.value
    val constants = cultureClass.enumConstants ?: return null

    return constants.firstOrNull { constant ->
      val enumName = (constant as Enum<*>).name
      enumName.equals(requestedValue, ignoreCase = true)
    } ?: constants.firstOrNull()
  }

  private fun applyExtraParams(paymentParams: Any, extra: Map<String, String>?) {
    val normalizedExtra = normalizeExtraParams(extra)
    if (normalizedExtra.isEmpty()) {
      return
    }

    setPropertyIfPresent(paymentParams, "extra", normalizedExtra)
    setPropertyIfPresent(paymentParams, "extraParams", normalizedExtra)
    setPropertyIfPresent(paymentParams, "customParams", normalizedExtra)

    val orderParams = resolveNestedParams(
      owner = paymentParams,
      getterNames = listOf("getOrderParams", "getOrder"),
      propertyName = "order",
      className = ORDER_PARAMS_CLASS
    ) ?: return

    setPropertyIfPresent(orderParams, "extra", normalizedExtra)
    setPropertyIfPresent(orderParams, "extraParams", normalizedExtra)
    setPropertyIfPresent(orderParams, "customParams", normalizedExtra)
  }

  private fun resolveNestedParams(
    owner: Any,
    getterNames: List<String>,
    propertyName: String,
    className: String
  ): Any? {
    getterNames.forEach { getterName ->
      invokeMethodIfPresent(owner, getterName)?.let { return it }
    }

    val paramsClass = loadOptionalClass(className) ?: return null
    val constructor = paramsClass.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() } ?: return null
    val paramsInstance = runCatching {
      constructor.isAccessible = true
      constructor.newInstance()
    }.getOrNull() ?: return null

    setPropertyIfPresent(owner, propertyName, paramsInstance)
    return paramsInstance
  }

  private fun normalizeExtraParams(extra: Map<String, String>?): Map<String, String> {
    if (extra.isNullOrEmpty()) {
      return emptyMap()
    }

    val normalized = linkedMapOf<String, String>()
    extra.forEach { (rawKey, rawValue) ->
      val key = rawKey.trim()
      val value = rawValue.trim()
      if (key.isEmpty() || value.isEmpty()) {
        return@forEach
      }

      val normalizedKey = normalizeShpKey(key)
      if (normalizedKey.isEmpty()) {
        return@forEach
      }

      val existingKey = normalized.keys.firstOrNull { it.equals(normalizedKey, ignoreCase = true) }
      if (existingKey != null) {
        normalized.remove(existingKey)
      }
      normalized[normalizedKey] = value
    }
    return normalized
  }

  private fun normalizeShpKey(key: String): String {
    val keyWithoutPrefix = if (key.startsWith("shp_", ignoreCase = true)) {
      key.substring(4).trim()
    } else {
      key.trim()
    }
    if (keyWithoutPrefix.isEmpty()) {
      return ""
    }
    return "Shp_$keyWithoutPrefix"
  }

  private fun extractErrorValue(errorPayload: Any?, candidates: List<String>): String? {
    if (errorPayload == null) {
      return null
    }

    val errorClass = errorPayload.javaClass
    candidates.forEach { candidate ->
      val accessor = errorClass.methods.firstOrNull { method ->
        method.name == candidate && method.parameterTypes.isEmpty()
      }

      val accessorValue = accessor?.let { method ->
        runCatching { method.invoke(errorPayload)?.toString() }.getOrNull()
      }
      if (!accessorValue.isNullOrBlank()) {
        return accessorValue
      }

      val field = errorClass.declaredFields.firstOrNull { it.name.equals(candidate, ignoreCase = true) }
      val fieldValue = field?.let { declaredField ->
        runCatching {
          declaredField.isAccessible = true
          declaredField.get(errorPayload)?.toString()
        }.getOrNull()
      }
      if (!fieldValue.isNullOrBlank()) {
        return fieldValue
      }
    }

    return errorPayload.toString().takeIf { it.isNotBlank() }
  }

  private fun invokeMethodIfPresent(target: Any, methodName: String, vararg values: Any?): Any? {
    val method = target.javaClass.methods.firstOrNull { candidate ->
      candidate.name == methodName && candidate.parameterTypes.size == values.size
    } ?: return null

    val arguments = method.parameterTypes.mapIndexed { index, parameterType ->
      coerceValue(parameterType, values[index])
    }.toTypedArray()

    return runCatching { method.invoke(target, *arguments) }.getOrNull()
  }

  private fun setPropertyIfPresent(target: Any, propertyName: String, value: Any?) {
    val setterName = "set${propertyName.replaceFirstChar { character -> character.uppercaseChar() }}"
    val setter = target.javaClass.methods.firstOrNull { method ->
      method.name.equals(setterName, ignoreCase = true) &&
        method.parameterTypes.size == 1
    }

    if (setter != null) {
      runCatching {
        setter.invoke(target, coerceValue(setter.parameterTypes[0], value))
      }
      return
    }

    var targetClass: Class<*>? = target.javaClass
    while (targetClass != null) {
      val field = targetClass.declaredFields.firstOrNull { candidate ->
        candidate.name.equals(propertyName, ignoreCase = true)
      }
      if (field != null) {
        runCatching {
          field.isAccessible = true
          field.set(target, coerceValue(field.type, value))
        }
        return
      }
      targetClass = targetClass.superclass
    }
  }

  private fun coerceValue(type: Class<*>, value: Any?): Any? {
    if (value == null) {
      return defaultValueForType(type)
    }

    if (type.isAssignableFrom(value.javaClass)) {
      return value
    }

    return when {
      type == String::class.java -> value.toString()
      type == Int::class.java || type == Int::class.javaPrimitiveType -> (value as? Number)?.toInt() ?: value.toString().toIntOrNull() ?: 0
      type == Double::class.java || type == Double::class.javaPrimitiveType -> (value as? Number)?.toDouble() ?: value.toString().toDoubleOrNull() ?: 0.0
      type == Float::class.java || type == Float::class.javaPrimitiveType -> (value as? Number)?.toFloat() ?: value.toString().toFloatOrNull() ?: 0f
      type == Long::class.java || type == Long::class.javaPrimitiveType -> (value as? Number)?.toLong() ?: value.toString().toLongOrNull() ?: 0L
      type == Boolean::class.java || type == Boolean::class.javaPrimitiveType -> value as? Boolean ?: value.toString().equals("true", ignoreCase = true)
      type.isEnum && value is String -> type.enumConstants?.firstOrNull { candidate ->
        (candidate as Enum<*>).name.equals(value, ignoreCase = true)
      } ?: defaultValueForType(type)
      else -> defaultValueForType(type)
    }
  }

  private fun defaultValueForType(type: Class<*>): Any? {
    return when (type) {
      String::class.java -> ""
      Int::class.javaPrimitiveType, Int::class.java -> 0
      Long::class.javaPrimitiveType, Long::class.java -> 0L
      Double::class.javaPrimitiveType, Double::class.java -> 0.0
      Float::class.javaPrimitiveType, Float::class.java -> 0f
      Boolean::class.javaPrimitiveType, Boolean::class.java -> false
      Short::class.javaPrimitiveType, Short::class.java -> 0.toShort()
      Byte::class.javaPrimitiveType, Byte::class.java -> 0.toByte()
      Char::class.javaPrimitiveType, Char::class.java -> '\u0000'
      else -> if (type.isEnum) type.enumConstants?.firstOrNull() else null
    }
  }

  private fun completePendingPayment(result: RobokassaPaymentResult) {
    val continuation = clearPendingState() ?: return
    runCatching {
      continuation.resume(result)
    }
  }

  private fun failPendingPayment(error: Throwable) {
    val continuation = clearPendingState() ?: return
    runCatching {
      continuation.resumeWithException(unwrapThrowable(error))
    }
  }

  private fun clearPendingState(): CancellableContinuation<RobokassaPaymentResult>? {
    synchronized(paymentLock) {
      val continuation = pendingPaymentContinuation
      pendingPaymentContinuation = null
      return continuation
    }
  }

  private fun loadRequiredClass(className: String): Class<*> {
    return loadOptionalClass(className) ?: throw RobokassaSdkMissingException(className)
  }

  private fun resolveRedirectUrl(activity: Activity): String {
    val context = activity.applicationContext
    val metaRedirectUrl = runCatching {
      val packageManager = context.packageManager
      val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getApplicationInfo(
          context.packageName,
          PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        )
      } else {
        @Suppress("DEPRECATION")
        packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
      }
      appInfo.metaData?.getString(REDIRECT_URL_META_DATA_NAME)
    }.getOrNull()

    return metaRedirectUrl?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_REDIRECT_URL
  }

  private fun loadOptionalClass(className: String): Class<*>? {
    return runCatching { Class.forName(className) }.getOrNull()
  }

  private fun isClassAvailable(className: String): Boolean {
    return loadOptionalClass(className) != null
  }

  private fun unwrapThrowable(throwable: Throwable): Throwable {
    return if (throwable is InvocationTargetException && throwable.targetException != null) {
      throwable.targetException
    } else {
      throwable
    }
  }

  companion object {
    private const val ROBOKASSA_ACTIVITY_CLASS = "com.robokassa.library.view.RobokassaActivity"
    private const val ROBOKASSA_ACTIVITY_REQUEST_CODE = 54731

    private const val EXTRA_PARAMS = "com.robokassa.PAYMENT_PARAMS"
    private const val EXTRA_ONLY_CHECK = "com.robokassa.ONLY_CHECK"
    private const val EXTRA_TEST_PARAMETERS = "com.robokassa.TEST_PARAMETERS"
    private const val EXTRA_INVOICE_ID = "com.robokassa.PAYMENT_INVOICE_ID"
    private const val EXTRA_ERROR = "com.robokassa.PAY_ERROR"
    private const val EXTRA_ERROR_DESC = "com.robokassa.PAYMENT_ERROR_DESC"
    private const val REDIRECT_URL_META_DATA_NAME = "robokassa.redirectUrl"
    private const val DEFAULT_REDIRECT_URL = "https://auth.robokassa.ru/Merchant/State/"

    private const val PAYMENT_PARAMS_CLASS = "com.robokassa.library.params.PaymentParams"
    private const val ORDER_PARAMS_CLASS = "com.robokassa.library.params.OrderParams"
    private const val CUSTOMER_PARAMS_CLASS = "com.robokassa.library.params.CustomerParams"
    private const val VIEW_PARAMS_CLASS = "com.robokassa.library.params.ViewParams"
    private val CULTURE_CLASS_CANDIDATES = listOf(
      "com.robokassa.library.params.Culture",
      "com.robokassa.library.models.Culture"
    )
  }
}
