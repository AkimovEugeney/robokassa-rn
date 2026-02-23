// Reexport the native module. On web, it will be resolved to RobokassaRnModule.web.ts
// and on native platforms to RobokassaRnModule.ts
export { default } from './RobokassaRnModule';
export { default as RobokassaRnView } from './RobokassaRnView';
export * from  './RobokassaRn.types';
