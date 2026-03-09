import { Platform } from 'react-native';
import { requireNativeModule, EventEmitter, type EventSubscription } from 'expo-modules-core';

export interface HashProgressEvent {
  progress: number;
  bytesRead: number;
  totalBytes: number;
}

export interface NativeUtilModuleType {
  startCalculateFileHash(fileUri: string): string;
  waitForJob(jobId: string): Promise<string>;
  cancelJob(jobId: string): Promise<void>;
  copyFile(srcUri: string, destUri: string): Promise<void>;
  startUploadFile(url: string, headers: Record<string, string>, fileUri: string): string;
  startDownloadFile(url: string, headers: Record<string, string>, fileUri: string): string;
}

const NativeUtilModule: NativeUtilModuleType = requireNativeModule('NativeUtilModule');

const eventEmitter = new EventEmitter<{
  onHashProgress: (event: HashProgressEvent) => void;
}>();

export const isNativeModuleAvailable = Platform.OS === 'android';
export const isNativeHashModuleAvailable = Platform.OS === 'android';

export async function nativeCopyFile(srcUri: string, destUri: string): Promise<void> {
  if (Platform.OS !== 'android') {
    throw new Error('NativeUtilModule is not available on this platform');
  }
  await NativeUtilModule.copyFile(srcUri, destUri);
}

export async function nativeCalculateFileHash(
  fileUri: string,
  signal?: AbortSignal,
  onProgress?: (progress: number) => void
): Promise<string> {
  if (Platform.OS !== 'android') {
    throw new Error('NativeUtilModule is not available on this platform');
  }

  let progressSub: EventSubscription | null = null;
  if (onProgress) {
    progressSub = eventEmitter.addListener('onHashProgress', (event: HashProgressEvent) => {
      onProgress(event.progress);
    });
  }

  const jobId = NativeUtilModule.startCalculateFileHash(fileUri);

  const abortHandler = () => NativeUtilModule.cancelJob(jobId);
  signal?.addEventListener('abort', abortHandler);

  try {
    const result = await NativeUtilModule.waitForJob(jobId);
    return result;
  } catch (error) {
    if (
      error instanceof Error &&
      ((error as { code?: string }).code === 'CANCELLED' ||
        error.message === 'Operation was cancelled')
    ) {
      const abortError = new Error('Operation was aborted');
      abortError.name = 'AbortError';
      throw abortError;
    }
    throw error;
  } finally {
    signal?.removeEventListener('abort', abortHandler);
    progressSub?.remove();
  }
}

export async function nativeUploadFile(
  url: string,
  headers: Record<string, string>,
  fileUri: string,
  signal?: AbortSignal
): Promise<void> {
  if (Platform.OS !== 'android') {
    throw new Error('NativeUtilModule is not available on this platform');
  }

  if (signal?.aborted) {
    throw new DOMException('Upload aborted', 'AbortError');
  }

  const jobId = NativeUtilModule.startUploadFile(url, headers, fileUri);

  const abortHandler = () => NativeUtilModule.cancelJob(jobId);
  signal?.addEventListener('abort', abortHandler);

  try {
    await NativeUtilModule.waitForJob(jobId);
  } finally {
    signal?.removeEventListener('abort', abortHandler);
  }
}

export async function nativeDownloadFile(
  url: string,
  headers: Record<string, string>,
  fileUri: string,
  signal?: AbortSignal
): Promise<void> {
  if (Platform.OS !== 'android') {
    throw new Error('NativeUtilModule is not available on this platform');
  }

  if (signal?.aborted) {
    throw new DOMException('Download aborted', 'AbortError');
  }

  const jobId = NativeUtilModule.startDownloadFile(url, headers, fileUri);

  const abortHandler = () => NativeUtilModule.cancelJob(jobId);
  signal?.addEventListener('abort', abortHandler);

  try {
    await NativeUtilModule.waitForJob(jobId);
  } finally {
    signal?.removeEventListener('abort', abortHandler);
  }
}
