import { registerWebModule, NativeModule } from 'expo';

import { RobokassaPaymentOptions, RobokassaPaymentResult, RobokassaRnModuleEvents } from './RobokassaRn.types';

class RobokassaRnModule extends NativeModule<RobokassaRnModuleEvents> {
  isRobokassaSdkAvailable(): boolean {
    return false;
  }

  async startPaymentAsync(_: RobokassaPaymentOptions): Promise<RobokassaPaymentResult> {
    throw new Error('robokassa-rn works only on Android');
  }
}

export default registerWebModule(RobokassaRnModule, 'RobokassaRn');
