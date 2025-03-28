package com.android.launcher3.popup;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.content.pm.SuspendDialogInfo.BUTTON_ACTION_UNSUSPEND;

import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_SYSTEM_SHORTCUT_APP_INFO_TAP;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_SYSTEM_SHORTCUT_WIDGETS_TAP;
import static com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION;
import static com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_TASK;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.SuspendDialogInfo;
import android.graphics.Rect;
import android.net.Uri;
import android.os.RemoteException;
import android.util.Log;
import android.view.InflateException;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.SplashScreen;
import android.os.UserHandle;

import androidx.annotation.Nullable;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BaseDraggingActivity;
import com.android.launcher3.Launcher;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.model.WidgetItem;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.util.InstantAppResolver;
import com.android.launcher3.util.PackageManagerHelper;
import com.android.launcher3.util.PackageUserKey;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.widget.WidgetsBottomSheet;
import com.android.launcher3.customization.InfoBottomSheet;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import java.net.URISyntaxException;
import java.util.List;

/**
 * Represents a system shortcut for a given app. The shortcut should have a label and icon, and an
 * onClickListener that depends on the item that the shortcut services.
 *
 * Example system shortcuts, defined as inner classes, include Widgets and AppInfo.
 * @param <T>
 */
public abstract class SystemShortcut<T extends Context & ActivityContext> extends ItemInfo
        implements View.OnClickListener {

    private static final String TAG = SystemShortcut.class.getSimpleName();
    private final int mIconResId;
    protected final int mLabelResId;
    protected int mAccessibilityActionId;

    protected final T mTarget;
    protected final ItemInfo mItemInfo;
    protected final View mOriginalView;

    /**
     * Indicates if it's invokable or not through some disabled UI
     */
    private boolean isEnabled = true;

    public SystemShortcut(int iconResId, int labelResId, T target, ItemInfo itemInfo,
            View originalView) {
        mIconResId = iconResId;
        mLabelResId = labelResId;
        mAccessibilityActionId = labelResId;
        mTarget = target;
        mItemInfo = itemInfo;
        mOriginalView = originalView;
    }

    public SystemShortcut(SystemShortcut<T> other) {
        mIconResId = other.mIconResId;
        mLabelResId = other.mLabelResId;
        mAccessibilityActionId = other.mAccessibilityActionId;
        mTarget = other.mTarget;
        mItemInfo = other.mItemInfo;
        mOriginalView = other.mOriginalView;
    }

    /**
     * Should be in the left group of icons in app's context menu header.
     */
    public boolean isLeftGroup() {
        return false;
    }

    public void setIconAndLabelFor(View iconView, TextView labelView) {
        iconView.setBackgroundResource(mIconResId);
        iconView.setEnabled(isEnabled);
        labelView.setText(mLabelResId);
        labelView.setEnabled(isEnabled);
    }

    public void setIconAndContentDescriptionFor(ImageView view) {
        view.setImageResource(mIconResId);
        view.setContentDescription(view.getContext().getText(mLabelResId));
        view.setEnabled(isEnabled);
    }

    public AccessibilityNodeInfo.AccessibilityAction createAccessibilityAction(Context context) {
        return new AccessibilityNodeInfo.AccessibilityAction(
                mAccessibilityActionId, context.getText(mLabelResId));
    }

    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public boolean hasHandlerForAction(int action) {
        return mAccessibilityActionId == action;
    }

    public interface Factory<T extends Context & ActivityContext> {

        @Nullable SystemShortcut<T> getShortcut(T activity, ItemInfo itemInfo, View originalView);
    }

    public static final Factory<Launcher> WIDGETS = (launcher, itemInfo, originalView) -> {
        if (itemInfo.getTargetComponent() == null) return null;
        final List<WidgetItem> widgets =
                launcher.getPopupDataProvider().getWidgetsForPackageUser(new PackageUserKey(
                        itemInfo.getTargetComponent().getPackageName(), itemInfo.user));
        if (widgets.isEmpty()) {
            return null;
        }
        return new Widgets(launcher, itemInfo, originalView);
    };

    public static class Widgets extends SystemShortcut<Launcher> {
        public Widgets(Launcher target, ItemInfo itemInfo, View originalView) {
            super(R.drawable.ic_widget, R.string.widget_button_text, target, itemInfo,
                    originalView);
        }

        @Override
        public void onClick(View view) {
            if (!Utilities.isWorkspaceEditAllowed(mTarget.getApplicationContext())) return;
            AbstractFloatingView.closeAllOpenViews(mTarget);
            WidgetsBottomSheet widgetsBottomSheet =
                    (WidgetsBottomSheet) mTarget.getLayoutInflater().inflate(
                            R.layout.widgets_bottom_sheet, mTarget.getDragLayer(), false);
            widgetsBottomSheet.populateAndShow(mItemInfo);
            mTarget.getStatsLogManager().logger().withItemInfo(mItemInfo)
                    .log(LAUNCHER_SYSTEM_SHORTCUT_WIDGETS_TAP);
        }
    }

    public static final Factory<BaseDraggingActivity> APP_INFO = AppInfo::new;

    public static class AppInfo<T extends Context & ActivityContext> extends SystemShortcut<T> {

        @Nullable
        private SplitAccessibilityInfo mSplitA11yInfo;

        public AppInfo(T target, ItemInfo itemInfo, View originalView) {
            super(R.drawable.ic_info_no_shadow, R.string.app_info_drop_target_label, target,
                    itemInfo, originalView);
        }

        /**
         * Constructor used by overview for staged split to provide custom A11y information.
         *
         * Future improvements considerations:
         * Have the logic in {@link #createAccessibilityAction(Context)} be moved to super
         * call in {@link SystemShortcut#createAccessibilityAction(Context)} by having
         * SystemShortcut be aware of TaskContainers and staged split.
         * That way it could directly create the correct node info for any shortcut that supports
         * split, but then we'll need custom resIDs for each pair of shortcuts.
         */
        public AppInfo(T target, ItemInfo itemInfo, View originalView,
                SplitAccessibilityInfo accessibilityInfo) {
            this(target, itemInfo, originalView);
            mSplitA11yInfo = accessibilityInfo;
            mAccessibilityActionId = accessibilityInfo.nodeId;
        }

        @Override
        public AccessibilityNodeInfo.AccessibilityAction createAccessibilityAction(
                Context context) {
            if (mSplitA11yInfo != null && mSplitA11yInfo.containsMultipleTasks) {
                String accessibilityLabel = context.getString(R.string.split_app_info_accessibility,
                        mSplitA11yInfo.taskTitle);
                return new AccessibilityNodeInfo.AccessibilityAction(mAccessibilityActionId,
                        accessibilityLabel);
            } else {
                return super.createAccessibilityAction(context);
            }
        }


        @Override
        public void onClick(View view) {
            InfoBottomSheet cbs;
            dismissTaskMenuView(mTarget);
            Rect sourceBounds = Utilities.getViewBounds(view);
            try {
                cbs = (InfoBottomSheet) mTarget.getLayoutInflater().inflate(
                        R.layout.app_info_bottom_sheet,
                        mTarget.getDragLayer(),
                        false);
                cbs.configureBottomSheet(sourceBounds, mTarget);
                cbs.populateAndShow(mItemInfo);
            } catch (InflateException e) {
                new PackageManagerHelper(mTarget).startDetailsActivityForInfo(
                        mItemInfo, sourceBounds, ActivityOptions.makeBasic().toBundle());
            }

            mTarget.getStatsLogManager().logger().withItemInfo(mItemInfo)
                    .log(LAUNCHER_SYSTEM_SHORTCUT_APP_INFO_TAP);
        }

        public static class SplitAccessibilityInfo {
            public final boolean containsMultipleTasks;
            public final CharSequence taskTitle;
            public final int nodeId;

            public SplitAccessibilityInfo(boolean containsMultipleTasks,
                    CharSequence taskTitle, int nodeId) {
                this.containsMultipleTasks = containsMultipleTasks;
                this.taskTitle = taskTitle;
                this.nodeId = nodeId;
            }
        }
    }

    public static final Factory<BaseDraggingActivity> INSTALL =
            (activity, itemInfo, originalView) -> {
                boolean supportsWebUI = (itemInfo instanceof WorkspaceItemInfo)
                        && ((WorkspaceItemInfo) itemInfo).hasStatusFlag(
                        WorkspaceItemInfo.FLAG_SUPPORTS_WEB_UI);
                boolean isInstantApp = false;
                if (itemInfo instanceof com.android.launcher3.model.data.AppInfo) {
                    com.android.launcher3.model.data.AppInfo
                            appInfo = (com.android.launcher3.model.data.AppInfo) itemInfo;
                    isInstantApp = InstantAppResolver.newInstance(activity).isInstantApp(appInfo);
                }
                boolean enabled = supportsWebUI || isInstantApp;
                if (!enabled) {
                    return null;
                }
                return new Install(activity, itemInfo, originalView);
    };

    public static class Install extends SystemShortcut<BaseDraggingActivity> {

        public Install(BaseDraggingActivity target, ItemInfo itemInfo, View originalView) {
            super(R.drawable.ic_install_no_shadow, R.string.install_drop_target_label,
                    target, itemInfo, originalView);
        }

        @Override
        public void onClick(View view) {
            Intent intent = new PackageManagerHelper(view.getContext()).getMarketIntent(
                    mItemInfo.getTargetComponent().getPackageName());
            mTarget.startActivitySafely(view, intent, mItemInfo);
            AbstractFloatingView.closeAllOpenViews(mTarget);
        }
    }

    public static final Factory<BaseDraggingActivity> PAUSE_APPS =
            (activity, itemInfo, originalView) -> {
                if (new PackageManagerHelper(activity).isAppSuspended(
                        itemInfo.getTargetComponent().getPackageName(), itemInfo.user)) {
                    return null;
                }
                return new PauseApps(activity, itemInfo, originalView);
    };

    public static class PauseApps<T extends Context & ActivityContext> extends SystemShortcut<T> {

        public PauseApps(T target, ItemInfo itemInfo, View originalView) {
            super(R.drawable.ic_hourglass, R.string.paused_apps_drop_target_label, target,
                    itemInfo, originalView);
        }

        @Override
        public void onClick(View view) {
            Context context = view.getContext();
            CharSequence appLabel = context.getPackageManager().getApplicationLabel(
                    new PackageManagerHelper(context).getApplicationInfo(
                            mItemInfo.getTargetComponent().getPackageName(), mItemInfo.user, 0));
            new AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.pause_apps_dialog_title,
                            appLabel))
                    .setMessage(context.getString(R.string.pause_apps_dialog_message,
                            appLabel))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.pause, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                AppGlobals.getPackageManager().setPackagesSuspendedAsUser(
                                        new String[]{
                                                mItemInfo.getTargetComponent().getPackageName()},
                                        true, null, null,
                                        new SuspendDialogInfo.Builder()
                                                .setTitle(R.string.paused_apps_dialog_title)
                                                .setMessage(R.string.paused_apps_dialog_message)
                                                .setNeutralButtonAction(BUTTON_ACTION_UNSUSPEND)
                                                .build(), 0, context.getOpPackageName(),
                                        context.getUserId(),
                                        mItemInfo.user.getIdentifier());
                            } catch (RemoteException e) {
                                Log.e(TAG, "Failed to pause app", e);
                            }
                        }
                    })
                    .show();
            AbstractFloatingView.closeAllOpenViews(mTarget);
        }
    }

    public static final Factory<BaseDraggingActivity> UNINSTALL = (activity, itemInfo, originalView) ->
            itemInfo.getTargetComponent() == null || PackageManagerHelper.isSystemApp(activity,
                 itemInfo.getTargetComponent().getPackageName())
                    ? null : new UnInstall(activity, itemInfo, originalView);

    public static class UnInstall extends SystemShortcut<BaseDraggingActivity> {

        public UnInstall(BaseDraggingActivity target, ItemInfo itemInfo, View originalView) {
            super(R.drawable.ic_uninstall_no_shadow, R.string.uninstall_drop_target_label,
                    target, itemInfo, originalView);
        }

        /**
         * @return the component name that should be uninstalled or null.
         */
        private ComponentName getUninstallTarget(ItemInfo item, Context context) {
            Intent intent = null;
            UserHandle user = null;
            if (item != null &&
                    (item.itemType == ITEM_TYPE_APPLICATION || item.itemType == ITEM_TYPE_TASK)) {
                intent = item.getIntent();
                user = item.user;
            }
            if (intent != null) {
                LauncherActivityInfo info = context.getSystemService(LauncherApps.class)
                        .resolveActivity(intent, user);
                if (info != null
                        && (info.getApplicationInfo().flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    return info.getComponentName();
                }
            }
            return null;
        }

        @Override
        public void onClick(View view) {
            ComponentName cn = getUninstallTarget(mItemInfo, view.getContext());
            if (cn == null) {
                // System applications cannot be installed. For now, show a toast explaining that.
                // We may give them the option of disabling apps this way.
                Toast.makeText(view.getContext(), R.string.uninstall_system_app_text, Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                Intent intent = Intent.parseUri(view.getContext().getString(R.string.delete_package_intent), 0)
                    .setData(Uri.fromParts("package", cn.getPackageName(), cn.getClassName()))
                    .putExtra(Intent.EXTRA_USER, mItemInfo.user);

                mTarget.startActivity(intent);
                AbstractFloatingView.closeAllOpenViews(mTarget);
            } catch (URISyntaxException e) {
                // Do nothing.
            }
        }
    }

    public static final Factory<BaseDraggingActivity> FREE_FORM = (activity, itemInfo, originalView) -> 
        ActivityManagerWrapper.getInstance().supportsFreeformMultiWindow(activity) 
        ? new FreeForm(activity, itemInfo, originalView)
        : null;

    public static class FreeForm extends SystemShortcut<BaseDraggingActivity> {
        private final String mPackageName;
        
        public FreeForm(BaseDraggingActivity target, ItemInfo itemInfo, View originalView) {
            super(R.drawable.ic_caption_desktop_button_foreground, R.string.recent_task_option_freeform, target, itemInfo, originalView);
            mPackageName = itemInfo.getTargetComponent().getPackageName();
        }

        @Override
        public void onClick(View view) {
            if (mPackageName != null) {
                Intent intent = mTarget.getPackageManager().getLaunchIntentForPackage(mPackageName);
                if (intent != null) {
                    ActivityOptions options = makeLaunchOptions(mTarget);
                    mTarget.startActivity(intent, options.toBundle());
                    AbstractFloatingView.closeAllOpenViews(mTarget);
                }
            }
        }

        private ActivityOptions makeLaunchOptions(Activity activity) {
            ActivityOptions activityOptions = ActivityOptions.makeBasic();
            activityOptions.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);
            final View decorView = activity.getWindow().getDecorView();
            final WindowInsets insets = decorView.getRootWindowInsets();
            final Rect r = new Rect(0, 0, decorView.getWidth() / 2, decorView.getHeight() / 2);
            r.offsetTo(insets.getSystemWindowInsetLeft() + 50, insets.getSystemWindowInsetTop() + 50);
            activityOptions.setLaunchBounds(r);
            activityOptions.setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_ICON);
            activityOptions.setTaskOverlay(true /* taskOverlay */, true /* canResume */);
            return activityOptions;
        }
    }

    public static <T extends Context & ActivityContext> void dismissTaskMenuView(T activity) {
        AbstractFloatingView.closeOpenViews(activity, true,
            AbstractFloatingView.TYPE_ALL & ~AbstractFloatingView.TYPE_REBIND_SAFE);
    }
}
