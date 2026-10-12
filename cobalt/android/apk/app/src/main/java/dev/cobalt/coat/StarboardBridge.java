// Copyright 2026 The Cobalt Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package dev.cobalt.coat;

import android.app.Activity;
import android.app.Service;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.hardware.input.InputManager;
import android.hardware.display.DisplayManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Rational;
import android.view.Display;
import android.view.Surface;
import android.view.InputDevice;
import android.view.accessibility.CaptioningManager;
import androidx.annotation.Nullable;
import dev.cobalt.media.AudioOutputManager;
import dev.cobalt.shell.StartupGuard;
import dev.cobalt.util.Holder;
import org.chromium.content_public.browser.WebContents;

/** Content-dependent StarboardBridge subclass used by AndroidTV. */
public class StarboardBridge extends BaseStarboardBridge {

  /** Interface to be implemented by the Android Application hosting the starboard app. */
  public interface HostApplication {
    void setStarboardBridge(StarboardBridge starboardBridge);

    StarboardBridge getStarboardBridge();
  }

  private final CobaltMediaSession mCobaltMediaSession;
  private final VolumeStateReceiver mVolumeStateReceiver;
  private volatile PlatformError mPlatformError;

  public StarboardBridge(
      Context appContext,
      Holder<Activity> activityHolder,
      Holder<Service> serviceHolder,
      ArtworkDownloader artworkDownloader,
      String[] args,
      String startDeepLink) {
    super(appContext, activityHolder, serviceHolder, args, startDeepLink);
    mCobaltMediaSession = new CobaltMediaSession(appContext, activityHolder, artworkDownloader);
    mVolumeStateReceiver = new VolumeStateReceiver(appContext);
  }

  @Override
  protected void onServiceCreated(CobaltService service) {
    super.onServiceCreated(service);
    service.receiveStarboardBridge(this);
  }

  @Override
  protected void onActivityStop(Activity activity) {
    super.onActivityStop(activity);
    if (isActivityLifecycleCoordinationEnabled()) {
      if (!hasStartedActivities()) {
        mCobaltMediaSession.onActivityStop();
      }
    } else {
      mCobaltMediaSession.onActivityStop();
    }
  }

  @Override
  void raisePlatformError(int errorType, long data, String url, boolean disableDismiss) {
    Activity activity = mActivityHolder.get();
    if (activity instanceof CobaltActivity cobaltActivity) {
      if (disableDismiss) {
        if (cobaltActivity.hasHiddenSplashScreen()) {
          android.util.Log.i(
              "StarboardBridge",
              "Ignoring platform error because splash screen has already been hidden.");
          return;
        }
      }
    }
    StartupGuard.getInstance().setStartupMilestone(StartupGuard.PLATFORM_ERROR_RAISED);
    mPlatformError = new PlatformError(mActivityHolder, errorType, data, url, disableDismiss);
    mPlatformError.raise();
  }

  @Override
  public boolean isPlatformErrorShowing() {
    if (mPlatformError != null) {
      return mPlatformError.isShowing();
    }
    return false;
  }

  public PlatformError getPlatformError() {
    return mPlatformError;
  }

  public void setWebContents(WebContents webContents) {
    mCobaltMediaSession.setWebContents(webContents);
    //mVolumeStateReceiver.setWebContents(webContents);
  }

  public CobaltMediaSession cobaltMediaSession() {
    return mCobaltMediaSession;
  }

  @CalledByNative
  protected boolean installAppFromURL(String url) {
    java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    executor.execute(() -> {
      java.io.File apkFile = new java.io.File(mAppContext.getFilesDir(), "downloaded_app.apk");
      boolean success = false;
      try (java.io.InputStream in = new java.net.URL(url).openStream();
           java.io.OutputStream out = new java.io.FileOutputStream(apkFile)) {
        byte[] buffer = new byte[8192];
        int len;
        long total = 0;
        while ((len = in.read(buffer)) != -1) {
          out.write(buffer, 0, len);
          total += len;
        }
        out.flush();
        success = true;
      } catch (Exception e) {
      }
      if (success) {
        mainHandler.post(() -> {
          try {
            android.net.Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                mAppContext,
                mAppContext.getPackageName() + ".fileprovider",
                apkFile);
            android.content.Intent installIntent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            installIntent.setDataAndType(contentUri, "application/vnd.android.package-archive");
            installIntent.setFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity = mActivityHolder.get();
            if (activity != null) {
              activity.startActivity(installIntent);
            } else {
              mAppContext.startActivity(installIntent);
            }
          } catch (Exception e) {
          }
        });
      } else {
      }
      executor.shutdown();
    });
    return true;
  }

  @CalledByNative
  protected String getVersion() {
    try {
      android.content.pm.PackageInfo packageInfo =
          mAppContext.getPackageManager().getPackageInfo(mAppContext.getPackageName(), 0);
      return packageInfo.versionName;
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      Log.e(TAG, "Failed to get package info", e);
      return "unknown";
    }
  }

  @CalledByNative
  protected String getBrandAndModel() {
    return Settings.Global.getString(mAppContext.getContentResolver(), "device_name");
  }

  @CalledByNative
  protected String getArchitecture() {
    return Build.SUPPORTED_ABIS[0];
  }

  @CalledByNative
  protected void setFrameRate(float frameRate) {
    if (Build.VERSION.SDK_INT < 30) {
      return;
    }

    Activity activity = mActivityHolder.get();
    if (activity == null) {
      return;
    }

    Display defaultDisplay = activity.getWindowManager().getDefaultDisplay();
    DisplayManager displayManager =
        (DisplayManager) mAppContext.getSystemService(Context.DISPLAY_SERVICE);

    boolean canBeSeamless = false;
    int strategy = 1;
    if (Build.VERSION.SDK_INT >= 31) {
        float[] supportedRefreshRates = defaultDisplay.getMode().getAlternativeRefreshRates();
        for (float rate : supportedRefreshRates) {
          if (Math.abs(rate - frameRate) < 0.1f || isMultiple(rate, frameRate, 0.1f)) {
            canBeSeamless = true;
            break;
          }
        }

        int contentPreference = displayManager.getMatchContentFrameRateUserPreference();
        if (contentPreference == DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER) return;
        else if (contentPreference == DisplayManager.MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY) {
          if (canBeSeamless) {
            strategy = Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS;
          }
        } else if (contentPreference == DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS) {
          strategy = Surface.CHANGE_FRAME_RATE_ALWAYS;
        } else {
          strategy = Surface.CHANGE_FRAME_RATE_ALWAYS;
        }
      }

    if (activity instanceof CobaltActivity) {
      if (Build.VERSION.SDK_INT == 30) {
        ((CobaltActivity) activity).setFrameRate(frameRate, 0);
      } else if (Build.VERSION.SDK_INT >= 31) {
        ((CobaltActivity) activity).setFrameRate(frameRate, strategy);
      }
    }
  }

  @CalledByNative
  protected void enterPIP() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Activity activity = mActivityHolder.get();
      if (activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
        Rational aspectRatio = new Rational(1920, 1080);
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .build();
            Boolean result = activity.enterPictureInPictureMode(params);
      }
    }
  }

  @CalledByNative
  protected boolean hasSystemFeature(String featureName) {
    return mAppContext.getPackageManager().hasSystemFeature(featureName);
  }

  private static boolean isMultiple(float refreshRate, float fps, float tolerance) {
    float ratio = refreshRate / fps;
    float nearestInt = Math.round(ratio);
    return Math.abs(ratio - nearestInt) < (tolerance / fps);
  }

  /** A wrapper of the android.util.Size class to be used by JNI. */
  public static class Size {
    private final int mWidth;
    private final int mHeight;

    public Size(int width, int height) {
      mWidth = width;
      mHeight = height;
    }

    @CalledByNative("Size")
    public int getWidth() {
      return mWidth;
    }

    @CalledByNative("Size")
    public int getHeight() {
      return mHeight;
    }
  }

  @Override
  protected void hideSplashScreen() {
    mPlatformError = null;
    if (mActivityHolder.get() instanceof CobaltActivity activity) {
      activity.onSplashScreenHidden();
    }
    StartupGuard.getInstance().disarm();
  }

  @Override
  protected void setStartupMilestone(@StartupGuard.Milestone int milestone) {
    StartupGuard.getInstance().setStartupMilestone(milestone);
  }

  @Override
  protected void setStartupDiagnosisInfo(String key, String value) {
    StartupGuard.getInstance().setDiagnosisInfo(key, value);
  }
}
