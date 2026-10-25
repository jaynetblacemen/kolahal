package com.mtwabpin.kolahal.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The add-a-widget flow, owned by MainActivity: allocate an id, bind it (silently when allowed,
 * otherwise through the system "allow Kolahal to create widgets" dialog), run the provider's
 * configure activity if it has one, then record it. Cancelling at any step frees the id.
 *
 * Must be constructed before the activity is started (it registers an activity-result launcher).
 * The configure step uses [android.appwidget.AppWidgetHost.startAppWidgetConfigureActivityForResult],
 * which reports through Activity.onActivityResult, so MainActivity forwards that to [onActivityResult].
 * The request survives Android stopping the launcher meanwhile (a widget's own setup screen is a
 * long visit): MainActivity keeps it in its saved state ([save], [restore]).
 */
class WidgetBinder(private val activity: ComponentActivity, private val onAdded: (PlacedWidget) -> Unit) {
    // Lazy: the binder is created before the activity has a context.
    private val widgets by lazy { WidgetHost.get(activity) }

    /** [info] is null for a request brought back from saved state: [infoOf] looks it up by the bound id. */
    private data class Request(val id: Int, val info: AppWidgetProviderInfo?, val replacing: String?)

    private fun infoOf(req: Request): AppWidgetProviderInfo? = req.info ?: runCatching { widgets.manager.getAppWidgetInfo(req.id) }.getOrNull()

    /** Keeps a request still waiting for the bind dialog or the widget's setup. */
    fun save(out: Bundle) {
        pending?.let {
            out.putInt(KEY_ID, it.id)
            out.putString(KEY_REPLACING, it.replacing)
        }
    }

    /** Brings back a request [save] kept, before the activity starts (and the host frees ids nobody uses). */
    fun restore(state: Bundle?) {
        val id = state?.getInt(KEY_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: return
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        widgets.resume(id)
        pending = Request(id, null, state.getString(KEY_REPLACING))
    }

    private var pending: Request? = null

    private val bindLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val req = pending ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) configure(req) else cancel(req, "Widget not added")
    }

    /** Starts adding [entry]; [replacing] is the key of a pending (restored) entry it fills in. */
    fun add(entry: ProviderEntry, replacing: String? = null) {
        pending?.let { widgets.discard(it.id) }
        val id = widgets.allocate()
        val req = Request(id, entry.info, replacing)
        pending = req
        val options = widgets.options(widgets.defaultHeightDp(entry.info))
        val bound = runCatching { widgets.manager.bindAppWidgetIdIfAllowed(id, entry.user, entry.info.provider, options) }.getOrDefault(false)
        if (bound) return configure(req)
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, entry.info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, entry.user)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)
        runCatching { bindLauncher.launch(intent) }.onFailure { cancel(req, "Can't ask to bind widgets: ${it.message}") }
    }

    private fun configure(req: Request) {
        val info = infoOf(req) ?: return cancel(req, "Widget not added")
        val optional = Build.VERSION.SDK_INT >= 31 &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (info.configure == null || optional) return finish(req)
        runCatching {
            widgets.host.startAppWidgetConfigureActivityForResult(activity, req.id, 0, REQUEST_CONFIGURE, null)
        }.onFailure { cancel(req, "Couldn't open the widget's setup: ${it.message ?: it.javaClass.simpleName}") }
    }

    /** Returns true when the result belonged to the configure step. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CONFIGURE) return false
        val req = pending ?: return true
        // Some configure activities return a different id in the result; ours is the one we bound.
        if (resultCode == Activity.RESULT_OK) finish(req) else cancel(req, "Widget setup cancelled")
        return true
    }

    private fun finish(req: Request) {
        val info = infoOf(req) ?: return cancel(req, "Widget not added")
        pending = null
        widgets.place(req.id, info, req.replacing)
        widgets.widgets.value.firstOrNull { it.id == req.id }?.let(onAdded)
        widgets.message("Added ${info.loadLabel(activity.packageManager)}")
    }

    private fun cancel(req: Request, why: String) {
        pending = null
        widgets.discard(req.id)
        widgets.message(why)
    }

    companion object {
        const val REQUEST_CONFIGURE = 0x4B50
        private const val KEY_ID = "widget_binder_id"
        private const val KEY_REPLACING = "widget_binder_replacing"
    }
}
