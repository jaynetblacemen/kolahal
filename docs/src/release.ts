import pkg from '../package.json';

/**
 * The release these pages describe, and its APKs on GitHub. The version is docs/package.json's,
 * which every release bumps together with the app, so the links follow each release.
 */
export const VERSION: string = pkg.version;

const REPO = 'https://github.com/mtwabpin/kolahal';
const DOWNLOAD = `${REPO}/releases/download/v${VERSION}`;

export const APK = `${DOWNLOAD}/kolahal-${VERSION}.apk`;
export const LITE_APK = `${DOWNLOAD}/kolahal-${VERSION}-lite.apk`;
export const RELEASES = `${REPO}/releases`;
