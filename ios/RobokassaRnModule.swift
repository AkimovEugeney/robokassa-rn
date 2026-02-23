import ExpoModulesCore

public class RobokassaRnModule: Module {
  public func definition() -> ModuleDefinition {
    Name("RobokassaRn")

    Function("isRobokassaSdkAvailable") {
      false
    }

    AsyncFunction("startPaymentAsync") { (_: [String: Any]) throws -> [String: Any] in
      throw Exception(name: "ERR_UNSUPPORTED_PLATFORM", description: "robokassa-rn works only on Android")
    }
  }
}
