package org.thunderdog.challegram.util;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Build;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;

import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.InstallState;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.ActivityResult;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallErrorCode;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;

import org.drinkless.td.libcore.telegram.TdApi;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.BuildConfig;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.ConnectionListener;
import org.thunderdog.challegram.telegram.FileUpdateListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibContext;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import me.vkryl.core.reference.ReferenceList;
import me.vkryl.td.Td;

public class AppUpdater implements InstallStateUpdatedListener, FileUpdateListener, ConnectionListener {
  public interface Listener {
    void onAppUpdateStateChanged (@State int state, @State int oldState, boolean isApk);
    default void onAppUpdateDownloadProgress (long bytesDownloaded, long totalBytesToDownload) { }
  }

  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    State.NONE,
    State.CHECKING,
    State.AVAILABLE,
    State.DOWNLOADING,
    State.READY_TO_INSTALL,
    State.INSTALLING
  })
  public @interface State {
    int NONE = 0; // No info whether there's an update
    int CHECKING = 1; // Checking whether there's any update
    int AVAILABLE = 2; // Available, but doing nothing
    int DOWNLOADING = 3; // Downloading an update
    int READY_TO_INSTALL = 4; // Update is downloaded and is ready to be installed
    int INSTALLING = 5;
  }

  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    FlowType.TELEGRAM_CHANNEL,
    FlowType.GOOGLE_PLAY
  })
  public @interface FlowType {
    int TELEGRAM_CHANNEL = 1;
    int GOOGLE_PLAY = 2;
  }

  private final BaseActivity context;
  private final ReferenceList<Listener> listeners;

  private final AppUpdateManager googlePlayUpdateManager;
  private AppUpdateInfo googlePlayUpdateInfo;
  private boolean googlePlayFlowError;

  private boolean updateOffered;

  private long bytesDownloaded, totalBytesToDownload;
  @FlowType
  private int flowType;

  private boolean forceTelegramChannelFlow;
  private Tdlib telegramChannelTdlib;
  private TdApi.Document telegramChannelFile;

  @State
  private int state = State.NONE;

  public AppUpdater (BaseActivity context) {
    this.context = context;
    this.listeners = new ReferenceList<>();
    AppUpdateManager appUpdateManager = null;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !BuildConfig.SIDE_LOAD_ONLY) {
      try {
        appUpdateManager = AppUpdateManagerFactory.create(context);
      } catch (Throwable t) {
        Log.e("Unable to initialize Google Play update manager", t);
      }
    }
    this.googlePlayUpdateManager = appUpdateManager;
  }

  @State
  public int state () {
    return state;
  }

  @FlowType
  public int flowType () {
    return flowType;
  }

  public long totalBytesToDownload () {
    return totalBytesToDownload;
  }

  public long bytesDownloaded () {
    return bytesDownloaded;
  }

  public void addListener (Listener listener) {
    listeners.add(listener);
  }

  public void removeListener (Listener listener) {
    listeners.remove(listener);
  }

  public void checkForUpdates () {
    if (state == State.NONE) {
      setState(State.CHECKING);
      if (preferTelegramChannelFlow()) {
        checkForTelegramChannelUpdates();
      } else {
        checkForGooglePlayUpdates();
      }
    } else if (state == State.AVAILABLE) {
      offerUpdate();
    }
  }

  private boolean preferTelegramChannelFlow () {
    // TODO: add server config to force
    return googlePlayUpdateManager == null ||
      forceTelegramChannelFlow ||
      (googlePlayFlowError && U.isAppSideLoaded());
  }

  private void setState (@State int state) {
    if (this.state != state) {
      final int oldState = this.state;
      this.state = state;
      for (Listener listener : listeners) {
        listener.onAppUpdateStateChanged(state, oldState, flowType == FlowType.TELEGRAM_CHANNEL);
      }
    }
  }

  private void checkForGooglePlayUpdates () {
    if (googlePlayUpdateManager == null)
      throw new IllegalStateException();
    googlePlayUpdateManager.getAppUpdateInfo().addOnSuccessListener(updateInfo -> {
      this.googlePlayUpdateInfo = updateInfo;
      int installStatus = updateInfo.installStatus();
      if (installStatus == InstallStatus.DOWNLOADED) {
        onUpdateAvailable(FlowType.GOOGLE_PLAY, updateInfo.bytesDownloaded(), updateInfo.totalBytesToDownload(), true);
      } else if (installStatus == InstallStatus.FAILED) {
        onGooglePlayFlowError();
      } else {
        int updateAvailability = updateInfo.updateAvailability();
        switch (updateAvailability) {
          case UpdateAvailability.UPDATE_AVAILABLE: {
            onUpdateAvailable(FlowType.GOOGLE_PLAY, updateInfo.bytesDownloaded(), updateInfo.totalBytesToDownload(), false);
            break;
          }
          case UpdateAvailability.UNKNOWN: {
            onGooglePlayFlowError();
            break;
          }
          case UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS:
          case UpdateAvailability.UPDATE_NOT_AVAILABLE: {
            if (U.isAppSideLoaded()) {
              onGooglePlayFlowError();
            } else {
              onUpdateUnavailable();
            }
            break;
          }
        }
      }
    }).addOnFailureListener(e -> {
      Log.i("Unable to check for app updates", e);
      onGooglePlayFlowError();
    });
  }

  private void onGooglePlayFlowError () {
    if (!this.googlePlayFlowError) {
      this.googlePlayFlowError = true;
      if (preferTelegramChannelFlow()) {
        checkForTelegramChannelUpdates();
      } else {
        onUpdateUnavailable();
      }
    }
  }

  private boolean offerGooglePlayUpdate () {
    int updateType;
    if (context.hasTdlib()) {
      Tdlib tdlib = context.currentTdlib();
      updateType = tdlib.context().inRecoveryMode() || !tdlib.context().hasActiveAccounts() ? AppUpdateType.IMMEDIATE : AppUpdateType.FLEXIBLE;
    } else {
      updateType = AppUpdateType.IMMEDIATE;
    }
    if (googlePlayUpdateInfo.isUpdateTypeAllowed(updateType)) {
      try {
        googlePlayUpdateManager.startUpdateFlowForResult(googlePlayUpdateInfo, updateType, context, Intents.ACTIVITY_RESULT_GOOGLE_PLAY_UPDATE);
        return true;
      } catch (IntentSender.SendIntentException e) {
        Log.e("Unable to offer an update", e);
      }
    }
    return false;
  }

  public void onGooglePlayFlowActivityResult (int resultCode, Intent data) {
    switch (resultCode) {
      case BaseActivity.RESULT_OK: {
        try {
          googlePlayUpdateManager.registerListener(this);
        } catch (Throwable t) {
          Log.e("Unable to register listener", t);
        }
        break;
      }
      case Activity.RESULT_CANCELED:
      case ActivityResult.RESULT_IN_APP_UPDATE_FAILED: {
        onGooglePlayFlowError();
        break;
      }
    }
  }

  @Override
  public void onStateUpdate (@NonNull InstallState state) {
    final int installStatus = state.installStatus();
    if (installStatus == InstallStatus.FAILED) {
      final int errorCode = state.installErrorCode();
      Log.i("Google Play update failed, code: %d", errorCode);
      if (errorCode == InstallErrorCode.ERROR_PLAY_STORE_NOT_FOUND ||
        errorCode == InstallErrorCode.ERROR_APP_NOT_OWNED) {
        forceTelegramChannelFlow = true;
      }
      onGooglePlayFlowError();
    } else if (installStatus == InstallStatus.DOWNLOADING) {
      onUpdateDownloading();
      onUpdateDownloadProgress(state.bytesDownloaded(), state.totalBytesToDownload());
    } else if (installStatus == InstallStatus.DOWNLOADED) {
      googlePlayUpdateManager.unregisterListener(this);
      onUpdateAvailable(FlowType.GOOGLE_PLAY, state.bytesDownloaded(), state.totalBytesToDownload(), true);
    }
  }

  private void checkForTelegramChannelUpdates () {
    Tdlib tdlib = context.hasTdlib() ? context.currentTdlib() : null;
    if (tdlib == null || tdlib.context().inRecoveryMode() || !tdlib.isAuthorized()) {
      onUpdateUnavailable();
      return;
    }
    tdlib.findUpdateFile(updateFile -> tdlib.ui().post(() -> {
      if (updateFile != null) {
        this.telegramChannelTdlib = tdlib;
        this.telegramChannelFile = updateFile;
        tdlib.listeners().addFileListener(updateFile.document.id, this);
        onUpdateAvailable(FlowType.TELEGRAM_CHANNEL, updateFile.document.local.downloadedSize, updateFile.document.expectedSize, TD.isFileLoadedAndExists(updateFile.document));
      } else {
        onUpdateUnavailable();
      }
    }));
  }

  private boolean offerTelegramChannelUpdate () {
    switch (Settings.instance().getAutoUpdateMode()) {
      case Settings.AUTO_UPDATE_MODE_PROMPT: {
        ViewController<?> c = context.navigation().getCurrentStackItem();
        if (c != null && c.isFocused()) {
          final long bytesToDownload = totalBytesToDownload() - bytesDownloaded();
          c.showOptions(new ViewController.Options.Builder().info(Lang.getMarkdownString(c, R.string.AppUpdateAvailablePrompt, Lang.boldCreator(), Strings.buildSize(bytesToDownload))).item(new ViewController.OptionItem(R.id.btn_update, Lang.getString(R.string.DownloadUpdate), ViewController.OPTION_COLOR_BLUE, R.drawable.baseline_system_update_24)).cancelItem().build(), (optionItemView, id) -> {
            if (id == R.id.btn_update) {
              downloadUpdate();
            }
            return true;
          });
          return true;
        }
        break;
      }
      case Settings.AUTO_UPDATE_MODE_ALWAYS: {
        downloadUpdate();
        break;
      }
      case Settings.AUTO_UPDATE_MODE_WIFI_ONLY: {
        if (telegramChannelTdlib.networkType() instanceof TdApi.NetworkTypeWiFi) {
          downloadUpdate();
        } else {
          telegramChannelTdlib.listeners().subscribeToConnectivityUpdates(this);
          return true;
        }
        break;
      }
      case Settings.AUTO_UPDATE_MODE_NEVER: {
        break;
      }
    }
    return false;
  }

  @Override
  public void onConnectionTypeChanged (TdApi.NetworkType type) {
    if (type instanceof TdApi.NetworkTypeWiFi) {
      telegramChannelTdlib.listeners().unsubscribeFromConnectivityUpdates(this);
      checkForUpdates();
    }
  }

  @Override
  public void onUpdateFile (TdApi.UpdateFile updateFile) {
    telegramChannelTdlib.ui().post(() -> {
      if (updateFile.file.id == telegramChannelFile.document.id) {
        Td.copyTo(updateFile.file, telegramChannelFile.document);
        if (TD.isFileLoadedAndExists(updateFile.file)) {
          onUpdateAvailable(FlowType.TELEGRAM_CHANNEL, updateFile.file.local.downloadedSize, updateFile.file.expectedSize, true);
        } else if (TD.isFileLoading(updateFile.file)) {
          onUpdateDownloading();
          onUpdateDownloadProgress(updateFile.file.local.downloadedSize, updateFile.file.expectedSize);
        } else {
          onUpdateDownloadCanceled();
        }
      } else {
        telegramChannelTdlib.listeners().removeFileListener(updateFile.file.id, this);
      }
    });
  }

  private void onUpdateAvailable (@FlowType int flowType, long bytesDownloaded, long totalBytesToDownload, boolean readyToInstall) {
    this.flowType = flowType;
    this.bytesDownloaded = bytesDownloaded;
    this.totalBytesToDownload = totalBytesToDownload;
    if (readyToInstall) {
      setState(State.READY_TO_INSTALL);
    } else {
      setState(State.AVAILABLE);
      offerUpdate();
    }
  }

  public void offerUpdate () {
    if (!updateOffered) {
      switch (flowType) {
        case FlowType.GOOGLE_PLAY: {
          updateOffered = offerGooglePlayUpdate();
          break;
        }
        case FlowType.TELEGRAM_CHANNEL: {
          updateOffered = offerTelegramChannelUpdate();
          break;
        }
      }
    }
  }

  public void downloadUpdate () {
    if (state != State.AVAILABLE) {
      return;
    }
    switch (flowType) {
      case FlowType.GOOGLE_PLAY: {
        updateOffered = offerGooglePlayUpdate();
        break;
      }
      case FlowType.TELEGRAM_CHANNEL: {
        // TODO add tdlib reference & show progress
        telegramChannelTdlib.files().downloadFile(telegramChannelFile.document);
        break;
      }
    }
  }

  private void onUpdateDownloadProgress (long bytesDownloaded, long totalBytesToDownload) {
    this.bytesDownloaded = bytesDownloaded;
    this.totalBytesToDownload = totalBytesToDownload;
    for (Listener listener : listeners) {
      listener.onAppUpdateDownloadProgress(bytesDownloaded, totalBytesToDownload);
    }
  }

  public void installUpdate () {
    if (state != State.READY_TO_INSTALL) {
      return;
    }
    switch (flowType) {
      case FlowType.TELEGRAM_CHANNEL: {
        // TODO guide on how to allow installing APKs
        UI.openFile(new TdlibContext(context, telegramChannelTdlib), telegramChannelFile.fileName, new File(telegramChannelFile.document.local.path), telegramChannelFile.mimeType, 0);
        break;
      }
      case FlowType.GOOGLE_PLAY: {
        googlePlayUpdateManager.completeUpdate();
        setState(State.INSTALLING);
        break;
      }
    }
  }

  private void onUpdateDownloading () {
    if (state != State.AVAILABLE)
      return;
    setState(State.DOWNLOADING);
  }

  private void onUpdateDownloadCanceled () {
    if (state != State.DOWNLOADING && state != State.READY_TO_INSTALL)
      return;
    setState(State.AVAILABLE);
  }

  private void onUpdateUnavailable () {
    setState(State.NONE);
  }
}