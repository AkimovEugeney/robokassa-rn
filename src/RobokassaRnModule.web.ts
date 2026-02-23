import { registerWebModule, NativeModule } from 'expo';

import { RobokassaRnModuleEvents } from './RobokassaRn.types';

class RobokassaRnModule extends NativeModule<RobokassaRnModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
}

export default registerWebModule(RobokassaRnModule, 'RobokassaRnModule');
