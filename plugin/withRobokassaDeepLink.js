const {
  AndroidConfig,
  createRunOncePlugin,
  withAndroidManifest,
  withAppBuildGradle,
  withSettingsGradle,
} = require('expo/config-plugins');

const pkg = require('../package.json');
const REDIRECT_URL_META_DATA_NAME = 'robokassa.redirectUrl';
const DEFAULT_REDIRECT_URL = 'https://auth.robokassa.ru/Merchant/State/';

function normalizePathPrefix(pathPrefix) {
  if (typeof pathPrefix !== 'string') {
    return undefined;
  }

  if (!pathPrefix.length || pathPrefix === '/') {
    return undefined;
  }

  return pathPrefix.startsWith('/') ? pathPrefix : `/${pathPrefix}`;
}

function toDataFromUrl(value) {
  const parsed = new URL(value);
  const scheme = parsed.protocol.replace(':', '');
  const pathPrefix = normalizePathPrefix(parsed.pathname);
  const data = {
    scheme,
  };

  if (parsed.hostname) {
    data.host = parsed.hostname;
  }
  if (parsed.port) {
    data.port = parsed.port;
  }
  if (pathPrefix) {
    data.pathPrefix = pathPrefix;
  }

  return data;
}

function toDeepLinkData(options) {
  const pathPrefix = normalizePathPrefix(options.deepLinkPathPrefix);
  const data = {
    scheme: options.deepLinkScheme,
  };

  if (options.deepLinkHost) {
    data.host = options.deepLinkHost;
  }
  if (pathPrefix) {
    data.pathPrefix = pathPrefix;
  }

  return data;
}

function createIntentFilter(data, autoVerify = false) {
  return {
    action: 'VIEW',
    category: ['BROWSABLE', 'DEFAULT'],
    autoVerify,
    data,
  };
}

function asArray(value) {
  if (Array.isArray(value)) {
    return value;
  }
  if (value == null) {
    return [];
  }
  return [value];
}

function fingerprintData(value) {
  return Object.entries(value || {})
    .map(([key, item]) => [key, String(item)])
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, item]) => `${key}=${item}`)
    .join('&');
}

function fingerprintIntentFilter(filter) {
  const action = typeof filter.action === 'string' ? filter.action : '';
  const categories = asArray(filter.category)
    .map((item) => String(item))
    .sort()
    .join(',');

  const data = asArray(filter.data)
    .map(fingerprintData)
    .sort()
    .join('|');

  const autoVerify = filter.autoVerify ? '1' : '0';

  return `${action}::${categories}::${data}::${autoVerify}`;
}

function normalizeOptions(options) {
  const returnUrls = Array.isArray(options.returnUrls)
    ? options.returnUrls.filter((value) => typeof value === 'string' && value.length > 0)
    : [];
  const redirectUrl = typeof options.redirectUrl === 'string' && options.redirectUrl.length > 0
    ? options.redirectUrl
    : returnUrls[0] ?? DEFAULT_REDIRECT_URL;

  return {
    configureAndroidSdk: options.configureAndroidSdk !== false,
    androidSdkModuleName: options.androidSdkModuleName ?? 'Robokassa_Library',
    androidSdkProjectDir:
      options.androidSdkProjectDir ?? '../node_modules/robokassa-rn/vendor/Robokassa_Library',
    addDefaultIntentFilter: options.addDefaultIntentFilter !== false,
    deepLinkScheme: options.deepLinkScheme ?? options.scheme ?? 'robokassa',
    deepLinkHost: options.deepLinkHost ?? options.host ?? 'open',
    deepLinkPathPrefix: options.deepLinkPathPrefix ?? options.pathPrefix,
    returnUrls,
    redirectUrl,
    autoVerify: options.autoVerify !== false,
  };
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function upsertGeneratedSection(contents, tag, block) {
  const begin = `// @generated begin ${tag}`;
  const end = `// @generated end ${tag}`;
  const section = `${begin}\n${block}\n${end}`;
  const matcher = new RegExp(`${escapeRegExp(begin)}[\\s\\S]*?${escapeRegExp(end)}\\n?`, 'm');

  if (matcher.test(contents)) {
    return contents.replace(matcher, `${section}\n`);
  }

  return `${contents.trimEnd()}\n\n${section}\n`;
}

function withRobokassaAndroidSdk(config, options) {
  if (!options.configureAndroidSdk) {
    return config;
  }

  config = withSettingsGradle(config, (config) => {
    const moduleName = options.androidSdkModuleName;
    const projectDir = options.androidSdkProjectDir;
    const isKotlinDsl = config.modResults.language === 'kt';

    const includeLine = isKotlinDsl ? `include(":${moduleName}")` : `include(':${moduleName}')`;
    const projectDirLine = isKotlinDsl
      ? `project(":${moduleName}").projectDir = file("${projectDir}")`
      : `project(':${moduleName}').projectDir = new File(rootProject.projectDir, '${projectDir}')`;

    config.modResults.contents = upsertGeneratedSection(
      config.modResults.contents,
      'robokassa-rn-settings',
      `${includeLine}\n${projectDirLine}`
    );

    return config;
  });

  return withAppBuildGradle(config, (config) => {
    const moduleName = options.androidSdkModuleName;
    const isKotlinDsl = config.modResults.language === 'kt';
    const dependencyLine = isKotlinDsl
      ? `implementation(project(":${moduleName}"))`
      : `implementation project(':${moduleName}')`;

    config.modResults.contents = upsertGeneratedSection(
      config.modResults.contents,
      'robokassa-rn-dependency',
      `dependencies {\n  ${dependencyLine}\n}`
    );

    return config;
  });
}

function withRobokassaIntentFilters(config, options) {
  const generatedFilters = [];

  if (options.addDefaultIntentFilter && options.deepLinkScheme) {
    generatedFilters.push(createIntentFilter(toDeepLinkData(options), false));
  }

  for (const value of options.returnUrls) {
    if (typeof value !== 'string' || !value.length) {
      continue;
    }

    let data;
    try {
      data = toDataFromUrl(value);
    } catch {
      continue;
    }
    const shouldVerify = options.autoVerify && data.scheme === 'https';
    generatedFilters.push(createIntentFilter(data, shouldVerify));
  }

  if (!generatedFilters.length) {
    return config;
  }

  config.android = config.android || {};
  const currentIntentFilters = Array.isArray(config.android.intentFilters) ? config.android.intentFilters : [];
  const seen = new Set(currentIntentFilters.map(fingerprintIntentFilter));
  const nextIntentFilters = [...currentIntentFilters];

  for (const filter of generatedFilters) {
    const fingerprint = fingerprintIntentFilter(filter);
    if (seen.has(fingerprint)) {
      continue;
    }

    seen.add(fingerprint);
    nextIntentFilters.push(filter);
  }

  config.android.intentFilters = nextIntentFilters;
  return config;
}

function withRobokassaRedirectMetaData(config, options) {
  return withAndroidManifest(config, (config) => {
    const mainApplication = AndroidConfig.Manifest.getMainApplicationOrThrow(config.modResults);
    AndroidConfig.Manifest.removeMetaDataItemFromMainApplication(
      mainApplication,
      REDIRECT_URL_META_DATA_NAME
    );
    AndroidConfig.Manifest.addMetaDataItemToMainApplication(
      mainApplication,
      REDIRECT_URL_META_DATA_NAME,
      options.redirectUrl,
      'value'
    );
    return config;
  });
}

const withRobokassaDeepLink = (config, props = {}) => {
  const options = normalizeOptions(props);
  config = withRobokassaIntentFilters(config, options);
  config = withRobokassaAndroidSdk(config, options);
  return withRobokassaRedirectMetaData(config, options);
};

module.exports = createRunOncePlugin(withRobokassaDeepLink, pkg.name, pkg.version);
