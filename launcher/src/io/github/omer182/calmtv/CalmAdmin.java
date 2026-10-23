package io.github.omer182.calmtv;

import android.app.admin.DeviceAdminReceiver;

/** Device admin with only "force-lock", so the sleep timer can put the TV into standby. */
public class CalmAdmin extends DeviceAdminReceiver {
}
