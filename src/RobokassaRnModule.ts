import { NativeModule, requireNativeModule } from 'expo';

import { RobokassaRnModuleEvents } from './RobokassaRn.types';

declare class RobokassaRnModule extends NativeModule<RobokassaRnModuleEvents> {
  PI: number;
  hello(): string;
  setValueAsync(value: string): Promise<void>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<RobokassaRnModule>('RobokassaRn');
