import { NativeModule, requireNativeModule } from 'expo';

import { RobokassaPaymentOptions, RobokassaPaymentResult, RobokassaRnModuleEvents } from './RobokassaRn.types';

declare class RobokassaRnModule extends NativeModule<RobokassaRnModuleEvents> {
  isRobokassaSdkAvailable(): boolean;
  startPaymentAsync(options: RobokassaPaymentOptions): Promise<RobokassaPaymentResult>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<RobokassaRnModule>('RobokassaRn');
