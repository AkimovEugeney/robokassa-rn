package expo.modules.robokassarn

import expo.modules.kotlin.exception.CodedException

internal class MissingCurrentActivityException :
  CodedException("Current Activity is not available. Open the app and try again.")

internal class PaymentInProgressException :
  CodedException("Payment is already in progress.")

internal class PaymentFlowInterruptedException :
  CodedException("Payment flow was interrupted before completion.")

internal class UnsupportedSdkVersionException(details: String) :
  CodedException(details)

internal class RobokassaSdkMissingException(className: String) :
  CodedException(
    "Robokassa SDK class '$className' is missing. Add Robokassa AAR into /android/libs and rebuild the app."
  )

internal class RobokassaSdkInvocationException(message: String, cause: Throwable) :
  CodedException(message, cause)
