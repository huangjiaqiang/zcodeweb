package com.zcoderemote.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/**
 * DownloadManager reports completion via a package-targeted broadcast; post our
 * own notification (the system one is hidden on completion) so tapping opens
 * the file directly.
 */
public class DownloadCompleteReceiver extends BroadcastReceiver {
    static final String TAG = "ZCRemote";

    @Override
    public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        if (id < 0) return;
        DownloadManager dm = context.getSystemService(DownloadManager.class);
        if (dm == null) return;
        Cursor c = null;
        try {
            c = dm.query(new DownloadManager.Query().setFilterById(id));
            if (!c.moveToFirst()) return;
            if (c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS))
                    != DownloadManager.STATUS_SUCCESSFUL) {
                return;
            }
            String title = c.getString(c.getColumnIndex(DownloadManager.COLUMN_TITLE));
            String mime = dm.getMimeTypeForDownloadedFile(id);
            Uri uri = dm.getUriForDownloadedFile(id);
            if (uri == null) {
                String local = c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                if (local != null) uri = Uri.parse(local);
            }
            Log.i(TAG, "download complete id=" + id + " uri=" + uri + " mime=" + mime);
            Notifications.downloaded(context, "已下载",
                    title == null || title.length() == 0 ? "文件" : title, uri, mime);
        } catch (Throwable t) {
            Log.w(TAG, "download complete handling failed " + t);
        } finally {
            if (c != null) c.close();
        }
    }
}
