import pkg from '../package.json';

/**
 * The release these pages describe, and its APKs on GitHub (the full build and the Play build). The version is site/package.json's,
 * which every release bumps together with the app, so the links follow each release.
 */
export const VERSION: string = pkg.version;

const REPO = 'https://github.com/mtwabpin/kolahal';
const DOWNLOAD = `${REPO}/releases/download/v${VERSION}`;

export const APK = `${DOWNLOAD}/kolahal-${VERSION}.apk`;
export const PLAY_APK = `${DOWNLOAD}/kolahal-${VERSION}-play.apk`;
export const RELEASES = `${REPO}/releases`;
