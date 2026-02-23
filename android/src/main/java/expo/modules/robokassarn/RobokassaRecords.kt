package expo.modules.robokassarn

import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import expo.modules.kotlin.types.Enumerable

enum class RobokassaCulture(val value: String) : Enumerable {
  RU("ru"),
  EN("en")
}

data class RobokassaPaymentOptions(
  @Field val merchantLogin: String,
  @Field val password1: String,
  @Field val password2: String,
  @Field val invoiceId: Int,
  @Field val orderSum: Double,
  @Field val description: String,
  @Field val email: String,
  @Field val culture: RobokassaCulture = RobokassaCulture.RU,
  @Field val isRecurrent: Boolean = false,
  @Field val isHold: Boolean = false,
  @Field val toolbarText: String? = null,
  @Field val previousInvoiceId: Int? = null,
  @Field val token: String? = null,
  @Field val extra: Map<String, String>? = null
) : Record

data class RobokassaPaymentResult(
  @Field val status: String,
  @Field val invoiceId: Int? = null,
  @Field val errorCode: String? = null,
  @Field val errorDescription: String? = null
) : Record
