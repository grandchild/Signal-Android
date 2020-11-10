package org.thoughtcrime.securesms.util;

import android.Manifest;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.os.StatFs;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.database.NoExternalStorageException;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

public class StorageUtil {

  private static final String TAG = StorageUtil.class.getSimpleName();

  private static final String PRODUCTION_PACKAGE_ID = "org.thoughtcrime.securesms";

  public static File getOrCreateBackupDirectory(@NonNull Context context) throws NoExternalStorageException {
    File backups = getBackupDirectory(context);

    if (!backups.exists()) {
      if (!backups.mkdirs()) {
        throw new NoExternalStorageException("Unable to create backup directory: " + backups);
      }
      if (!backups.canWrite()) {
        throw new NoExternalStorageException("Unable to write to backup directory: " + backups);
      }
    }

    return backups;
  }

  private static @Nullable File getSDCardOrExternalDirectory(@NonNull Context context) {
    File sdStorage = null;

    if (Build.VERSION.SDK_INT >= 19) {
      File[] sdCardDirectories = getSDCardDirectories(context);

      if (sdCardDirectories != null) {
        sdStorage = getNonEmulated(sdCardDirectories);
        if(sdStorage != null) {
          Log.d(TAG, "sdcard storage detected: " + sdStorage.getAbsolutePath());
        }
      }
    }

    File externalStorage = Environment.getExternalStorageDirectory();

    if(sdStorage == null) {
      return externalStorage;
    }

    StatFs statExternal = new StatFs(externalStorage.getAbsolutePath());
    StatFs statSDCard = new StatFs(sdStorage.getAbsolutePath());
    Log.d(TAG, "ext MiB free: " + statExternal.getAvailableBytes() / 1024 / 1024);
    Log.d(TAG, "sd MiB free: " + statSDCard.getAvailableBytes() / 1024 / 1024);
    if(statExternal.getAvailableBytes() < statSDCard.getAvailableBytes()) {
      Log.i(TAG, "selecting SDCard for backups");
      return sdStorage;
    }
    Log.i(TAG, "selecting external storage for backups");
    return externalStorage;
  }

  public static @Nullable File[] getSDCardDirectories(@NonNull Context context) {
    File[] dirs = ContextCompat.getExternalFilesDirs(context, null);
    for(File dir : dirs) {
      Log.d(TAG, dir.getAbsolutePath());
    }
    ArrayList<File> paths = new ArrayList<>();
    try {
      Scanner scanner = new Scanner(new File("/proc/mounts"));
      while (scanner.hasNext()) {
        String line = scanner.nextLine();
        if (line.contains(" /storage/")) {
          String[] lineElements = line.split(" ");
          String mountPoint = lineElements[1];

          if (mountPoint.startsWith("/storage/emulated")) {
            continue;
          }

          paths.add(new File(mountPoint + "/Android/data/" + BuildConfig.APPLICATION_ID + "/files/"));
        }
      }
    } catch(FileNotFoundException fnfe) {
      return null;
    }
    return paths.toArray(new File[0]);
  }

  public static File getBackupDirectory(@NonNull Context context) throws NoExternalStorageException {
    File storage = getSDCardOrExternalDirectory(context);
    File signal  = new File(storage, "Signal");
    File backups = new File(signal, "Backups");

    //noinspection ConstantConditions
    if (BuildConfig.APPLICATION_ID.startsWith(PRODUCTION_PACKAGE_ID + ".")) {
      backups = new File(backups, BuildConfig.APPLICATION_ID.substring(PRODUCTION_PACKAGE_ID.length() + 1));
    }

    return backups;
  }

  @RequiresApi(24)
  public static @NonNull String getDisplayPath(@NonNull Context context, @NonNull Uri uri) {
    String lastPathSegment = Objects.requireNonNull(uri.getLastPathSegment());
    String backupVolume    = lastPathSegment.replaceFirst(":.*", "");
    String backupName      = lastPathSegment.replaceFirst(".*:", "");

    StorageManager      storageManager = ServiceUtil.getStorageManager(context);
    List<StorageVolume> storageVolumes = storageManager.getStorageVolumes();
    StorageVolume       storageVolume  = null;

    for (StorageVolume volume : storageVolumes) {
      if (Objects.equals(volume.getUuid(), backupVolume)) {
        storageVolume = volume;
        break;
      }
    }

    if (storageVolume == null) {
      return backupName;
    } else {
      return context.getString(R.string.StorageUtil__s_s, storageVolume.getDescription(context), backupName);
    }
  }

  public static File getBackupCacheDirectory(Context context) {
    if (Build.VERSION.SDK_INT >= 19) {
      File[] directories = context.getExternalCacheDirs();

      if (directories != null) {
        File result = getNonEmulated(directories);
        if (result != null) return result;
      }
    }

    return context.getExternalCacheDir();
  }

  private static @Nullable File getNonEmulated(File[] directories) {
    return Stream.of(directories)
                 .withoutNulls()
                 .filterNot(f -> f.getAbsolutePath().contains("emulated"))
                 .limit(1)
                 .findSingle()
                 .orElse(null);
  }

  private static File getSignalStorageDir() throws NoExternalStorageException {
    final File storage = Environment.getExternalStorageDirectory();

    if (!storage.canWrite()) {
      throw new NoExternalStorageException();
    }

    return storage;
  }

  public static boolean canWriteInSignalStorageDir() {
    File storage;

    try {
      storage = getSignalStorageDir();
    } catch (NoExternalStorageException e) {
      return false;
    }

    return storage.canWrite();
  }

  public static File getLegacyBackupDirectory() throws NoExternalStorageException {
    return getSignalStorageDir();
  }

  public static boolean canWriteToMediaStore() {
    return Build.VERSION.SDK_INT > 28 ||
           Permissions.hasAll(ApplicationDependencies.getApplication(), Manifest.permission.WRITE_EXTERNAL_STORAGE);
  }

  public static boolean canReadFromMediaStore() {
    return Permissions.hasAll(ApplicationDependencies.getApplication(), Manifest.permission.READ_EXTERNAL_STORAGE);
  }

  public static @NonNull Uri getVideoUri() {
    if (Build.VERSION.SDK_INT < 21) {
      return getLegacyUri(Environment.DIRECTORY_MOVIES);
    } else {
      return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    }
  }

  public static @NonNull Uri getAudioUri() {
    if (Build.VERSION.SDK_INT < 29) {
      return getLegacyUri(Environment.DIRECTORY_MUSIC);
    } else {
      return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
    }
  }

  public static @NonNull Uri getImageUri() {
    if (Build.VERSION.SDK_INT < 21) {
      return getLegacyUri(Environment.DIRECTORY_PICTURES);
    } else {
      return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }
  }

  public static @NonNull Uri getDownloadUri() {
    if (Build.VERSION.SDK_INT < 29) {
      return getLegacyUri(Environment.DIRECTORY_DOWNLOADS);
    } else {
      return MediaStore.Downloads.EXTERNAL_CONTENT_URI;
    }
  }

  public static @NonNull Uri getLegacyUri(@NonNull String directory) {
    return Uri.fromFile(Environment.getExternalStoragePublicDirectory(directory));
  }

  public static @Nullable String getCleanFileName(@Nullable String fileName) {
    if (fileName == null) return null;

    fileName = fileName.replace('\u202D', '\uFFFD');
    fileName = fileName.replace('\u202E', '\uFFFD');

    return fileName;
  }
}
