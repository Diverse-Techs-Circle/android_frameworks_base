/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import android.app.AlarmManager;
import android.app.NotificationHistory;
import android.app.NotificationHistory.HistoricalNotification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

/**
 * Provides an interface to write and query for notification history data for a user from a Protocol
 * Buffer database.
 *
 * Periodically writes the buffered history to disk but can also accept force writes based on
 * outside changes (like a pending shutdown).
 */
public class NotificationHistoryDatabase {
    private static final int DEFAULT_CURRENT_VERSION = 1;

    private static final String TAG = "NotiHistoryDatabase";
    private static final boolean DEBUG = NotificationManagerService.DBG;
    private static final int HISTORY_RETENTION_DAYS = 2;
    private static final int HISTORY_RETENTION_MS = 24 * 60 * 60 * 1000;
    private static final long WRITE_BUFFER_INTERVAL_MS = 1000 * 60 * 20;

    private static final String ACTION_HISTORY_DELETION =
            NotificationHistoryDatabase.class.getSimpleName() + ".CLEANUP";
    private static final int REQUEST_CODE_DELETION = 1;
    private static final String SCHEME_DELETION = "delete";
    private static final String EXTRA_KEY = "key";

    private final Context mContext;
    private final AlarmManager mAlarmManager;
    private final Object mLock = new Object();
    private final Handler mFileWriteHandler;
    @VisibleForTesting
    // List of files holding history information, sorted newest to oldest
    final LinkedList<AtomicFile> mHistoryFiles;
    private final File mHistoryDir;
    private final File mVersionFile;
    // Current version of the database files schema
    private int mCurrentVersion;
    private final WriteBufferRunnable mWriteBufferRunnable;
    private final FileAttrProvider mFileAttrProvider;

    // Object containing posted notifications that have not yet been written to disk
    @VisibleForTesting
    NotificationHistory mBuffer;

    public NotificationHistoryDatabase(Context context, Handler fileWriteHandler, File dir,
            FileAttrProvider fileAttrProvider) {
        mContext = context;
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mCurrentVersion = DEFAULT_CURRENT_VERSION;
        mFileWriteHandler = fileWriteHandler;
        mVersionFile = new File(dir, "version");
        mHistoryDir = new File(dir, "history");
        mHistoryFiles = new LinkedList<>();
        mBuffer = new NotificationHistory();
        mWriteBufferRunnable = new WriteBufferRunnable();
        mFileAttrProvider = fileAttrProvider;

        IntentFilter deletionFilter = new IntentFilter(ACTION_HISTORY_DELETION);
        deletionFilter.addDataScheme(SCHEME_DELETION);
        mContext.registerReceiver(mFileCleaupReceiver, deletionFilter);
    }

    public void init() {
        synchronized (mLock) {
            try {
                mHistoryDir.mkdir();
                mVersionFile.createNewFile();
            } catch (Exception e) {
                Slog.e(TAG, "could not create needed files", e);
            }

            checkVersionAndBuildLocked();
            indexFilesLocked();
            prune(HISTORY_RETENTION_DAYS, System.currentTimeMillis());
        }
    }

    private void indexFilesLocked() {
        mHistoryFiles.clear();
        final File[] files = mHistoryDir.listFiles();
        if (files == null) {
            return;
        }

        // Sort with newest files first
        Arrays.sort(files, (lhs, rhs) -> Long.compare(mFileAttrProvider.getCreationTime(rhs),
                mFileAttrProvider.getCreationTime(lhs)));

        for (File file : files) {
            mHistoryFiles.addLast(new AtomicFile(file));
        }
    }

    private void checkVersionAndBuildLocked() {
        int version;
        try (BufferedReader reader = new BufferedReader(new FileReader(mVersionFile))) {
            version = Integer.parseInt(reader.readLine());
        } catch (NumberFormatException | IOException e) {
            version = 0;
        }

        if (version != mCurrentVersion && mVersionFile.exists()) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(mVersionFile))) {
                writer.write(Integer.toString(mCurrentVersion));
                writer.write("\n");
                writer.flush();
            } catch (IOException e) {
                Slog.e(TAG, "Failed to write new version");
                throw new RuntimeException(e);
            }
        }
    }

    public void forceWriteToDisk() {
        if (!mFileWriteHandler.hasCallbacks(mWriteBufferRunnable)) {
            mFileWriteHandler.post(mWriteBufferRunnable);
        }
    }

    public void onPackageRemoved(String packageName) {
        RemovePackageRunnable rpr = new RemovePackageRunnable(packageName);
        mFileWriteHandler.post(rpr);
    }

    public void addNotification(final HistoricalNotification notification) {
        synchronized (mLock) {
            mBuffer.addNotificationToWrite(notification);
            // Each time we have new history to write to disk, schedule a write in [interval] ms
            if (mBuffer.getHistoryCount() == 1) {
                mFileWriteHandler.postDelayed(mWriteBufferRunnable, WRITE_BUFFER_INTERVAL_MS);
            }
        }
    }

    public NotificationHistory readNotificationHistory() {
        synchronized (mLock) {
            NotificationHistory notifications = new NotificationHistory();

            for (AtomicFile file : mHistoryFiles) {
                try {
                    readLocked(
                            file, notifications, new NotificationHistoryFilter.Builder().build());
                } catch (Exception e) {
                    Slog.e(TAG, "error reading " + file.getBaseFile().getName(), e);
                }
            }

            return notifications;
        }
    }

    public NotificationHistory readNotificationHistory(String packageName, String channelId,
            int maxNotifications) {
        synchronized (mLock) {
            NotificationHistory notifications = new NotificationHistory();

            for (AtomicFile file : mHistoryFiles) {
                try {
                    readLocked(file, notifications,
                            new NotificationHistoryFilter.Builder()
                                    .setPackage(packageName)
                                    .setChannel(packageName, channelId)
                                    .setMaxNotifications(maxNotifications)
                                    .build());
                    if (maxNotifications == notifications.getHistoryCount()) {
                        // No need to read any more files
                        break;
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "error reading " + file.getBaseFile().getName(), e);
                }
            }

            return notifications;
        }
    }

    /**
     * Remove any files that are too old and schedule jobs to clean up the rest
     */
    void prune(final int retentionDays, final long currentTimeMillis) {
        synchronized (mLock) {
            GregorianCalendar retentionBoundary = new GregorianCalendar();
            retentionBoundary.setTimeInMillis(currentTimeMillis);
            retentionBoundary.add(Calendar.DATE, -1 * retentionDays);

            for (int i = mHistoryFiles.size() - 1; i >= 0; i--) {
                final AtomicFile currentOldestFile = mHistoryFiles.get(i);
                final long creationTime =
                        mFileAttrProvider.getCreationTime(currentOldestFile.getBaseFile());
                if (creationTime <= retentionBoundary.getTimeInMillis()) {
                    if (DEBUG) {
                        Slog.d(TAG, "Removed " + currentOldestFile.getBaseFile().getName());
                    }
                    currentOldestFile.delete();
                    mHistoryFiles.removeLast();
                } else {
                    // all remaining files are newer than the cut off; schedule jobs to delete
                    final long deletionTime = creationTime + (retentionDays * HISTORY_RETENTION_MS);
                    scheduleDeletion(currentOldestFile.getBaseFile(), deletionTime);
                }
            }
        }
    }

    private void scheduleDeletion(File file, long deletionTime) {
        if (DEBUG) {
            Slog.d(TAG, "Scheduling deletion for " + file.getName() + " at " + deletionTime);
        }
        final PendingIntent pi = PendingIntent.getBroadcast(mContext,
                REQUEST_CODE_DELETION,
                new Intent(ACTION_HISTORY_DELETION)
                        .setData(new Uri.Builder().scheme(SCHEME_DELETION)
                                .appendPath(file.getAbsolutePath()).build())
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra(EXTRA_KEY, file.getAbsolutePath()),
                PendingIntent.FLAG_UPDATE_CURRENT);
        mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deletionTime, pi);
    }

    private void writeLocked(AtomicFile file, NotificationHistory notifications)
            throws IOException {
        FileOutputStream fos = file.startWrite();
        try {
            NotificationHistoryProtoHelper.write(fos, notifications, mCurrentVersion);
            file.finishWrite(fos);
            fos = null;
        } finally {
            // When fos is null (successful write), this will no-op
            file.failWrite(fos);
        }
    }

    private static void readLocked(AtomicFile file, NotificationHistory notificationsOut,
            NotificationHistoryFilter filter) throws IOException {
        try (FileInputStream in = file.openRead()) {
            NotificationHistoryProtoHelper.read(in, notificationsOut, filter);
        } catch (FileNotFoundException e) {
            Slog.e(TAG, "Cannot file " + file.getBaseFile().getName(), e);
            throw e;
        }
    }

    private final BroadcastReceiver mFileCleaupReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            if (ACTION_HISTORY_DELETION.equals(action)) {
                try {
                    final String filePath = intent.getStringExtra(EXTRA_KEY);
                    AtomicFile fileToDelete = new AtomicFile(new File(filePath));
                    fileToDelete.delete();
                } catch (Exception e) {
                    Slog.e(TAG, "Failed to delete notification history file", e);
                }
            }
        }
    };

    private final class WriteBufferRunnable implements Runnable {
        @Override
        public void run() {
            if (DEBUG) Slog.d(TAG, "WriteBufferRunnable");
            synchronized (mLock) {
                final AtomicFile latestNotificationsFiles = new AtomicFile(
                        new File(mHistoryDir, String.valueOf(System.currentTimeMillis())));
                try {
                    writeLocked(latestNotificationsFiles, mBuffer);
                    mHistoryFiles.addFirst(latestNotificationsFiles);
                    mBuffer = new NotificationHistory();
                } catch (IOException e) {
                    Slog.e(TAG, "Failed to write buffer to disk. not flushing buffer", e);
                }
            }
        }
    }

    private final class RemovePackageRunnable implements Runnable {
        private String mPkg;

        public RemovePackageRunnable(String pkg) {
            mPkg = pkg;
        }

        @Override
        public void run() {
            if (DEBUG) Slog.d(TAG, "RemovePackageRunnable");
            synchronized (mLock) {
                // Remove packageName entries from pending history
                mBuffer.removeNotificationsFromWrite(mPkg);

                Iterator<AtomicFile> historyFileItr = mHistoryFiles.iterator();
                while (historyFileItr.hasNext()) {
                    final AtomicFile af = historyFileItr.next();
                    try {
                        final NotificationHistory notifications = new NotificationHistory();
                        readLocked(af, notifications,
                                new NotificationHistoryFilter.Builder().build());
                        notifications.removeNotificationsFromWrite(mPkg);
                        writeLocked(af, notifications);
                    } catch (Exception e) {
                        Slog.e(TAG, "Cannot clean up file on pkg removal "
                                + af.getBaseFile().getName(), e);
                    }
                }
            }
        }
    }

    public static final class NotificationHistoryFileAttrProvider implements
            NotificationHistoryDatabase.FileAttrProvider {
        final static String TAG = "NotifHistoryFileDate";

        public long getCreationTime(File file) {
            try {
                BasicFileAttributes attr = Files.readAttributes(FileSystems.getDefault().getPath(
                        file.getAbsolutePath()), BasicFileAttributes.class);
                return attr.creationTime().to(TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                Slog.w(TAG, "Cannot read creation data for file; using file name");
                return Long.valueOf(file.getName());
            }
        }
    }

    interface FileAttrProvider {
        long getCreationTime(File file);
    }
}
