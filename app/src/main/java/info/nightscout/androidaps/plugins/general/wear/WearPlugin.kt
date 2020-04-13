package info.nightscout.androidaps.plugins.general.wear

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.widget.Toast
import dagger.Lazy
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.MainApp
import info.nightscout.androidaps.R
import info.nightscout.androidaps.events.*
import info.nightscout.androidaps.interfaces.PluginBase
import info.nightscout.androidaps.interfaces.PluginDescription
import info.nightscout.androidaps.interfaces.PluginType
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.plugins.aps.events.EventOpenAPSUpdateGui
import info.nightscout.androidaps.plugins.aps.loop.LoopPlugin
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.plugins.general.overview.events.EventDismissBolusProgressIfRunning
import info.nightscout.androidaps.plugins.general.overview.events.EventOverviewBolusProgress
import info.nightscout.androidaps.plugins.general.wear.tizenintegration.TizenUpdaterService
import info.nightscout.androidaps.plugins.general.wear.wearintegration.WatchUpdaterService
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.events.EventAutosensCalculationFinished
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.sharedPreferences.SP
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WearPlugin @Inject constructor(
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    resourceHelper: ResourceHelper,
    private val sp: SP,
    private val mainApp: MainApp,
    private val fabricPrivacy: FabricPrivacy,
    private val loopPlugin: Lazy<LoopPlugin>,
    private val rxBus: RxBusWrapper

) : PluginBase(PluginDescription()
    .mainType(PluginType.GENERAL)
    .fragmentClass(WearFragment::class.java.name)
    .pluginName(R.string.wear)
    .shortName(R.string.wear_shortname)
    .preferencesId(R.xml.pref_wear)
    .description(R.string.description_wear),
    aapsLogger, resourceHelper, injector
) {
    private val TAG = "Tizen plugin"
    private var tizenUS: TizenUpdaterService? = null
    private var mIsBound = false

    private val disposable = CompositeDisposable()
    override fun onStart() {
        /* Todo Code disable, should be inside TizenUpdaterService in tizenApiConnect function
        if (sp.getBoolean(TizenUpdaterService.TIZEN_ENABLE, false)) {
            // Bind service
            mIsBound = mainApp.bindService(Intent(mainApp, TizenUpdaterService::class.java), mConnection, Context.BIND_AUTO_CREATE)
            //Toast.makeText(mainApp, "Launch Tizen callback on Startup", Toast.LENGTH_LONG).show()
        }
         */
        super.onStart()
        disposable.add(rxBus
            .toObservable(EventOpenAPSUpdateGui::class.java)
            .observeOn(Schedulers.io())
            .subscribe({ sendDataToWatch(status = true, basals = true, bgValue = false) }) { fabricPrivacy.logException(it) })
        disposable.add(rxBus
            .toObservable(EventExtendedBolusChange::class.java)
            .observeOn(Schedulers.io())
            .subscribe({ sendDataToWatch(status = true, basals = true, bgValue = false) }) { fabricPrivacy.logException(it) })
        disposable.add(rxBus
            .toObservable(EventTempBasalChange::class.java)
            .observeOn(Schedulers.io())
            .subscribe({ sendDataToWatch(status = true, basals = true, bgValue = false) }) { fabricPrivacy.logException(it) })
        disposable.add(rxBus
            .toObservable(EventTreatmentChange::class.java)
            .observeOn(Schedulers.io())
            .subscribe({ sendDataToWatch(status = true, basals = true, bgValue = false) }) { fabricPrivacy.logException(it) })
        disposable.add(rxBus
            .toObservable(EventNewBasalProfile::class.java)
            .observeOn(Schedulers.io())
            .subscribe({ sendDataToWatch(status = false, basals = true, bgValue = false) }) { fabricPrivacy.logException(it) })
        disposable.add(rxBus
            .toObservable(EventAutosensCalculationFinished::class.java)
            .observeOn(Schedulers.io())
            .subscribe({ sendDataToWatch(status = true, basals = true, bgValue = true) }) { fabricPrivacy.logException(it) })
        disposable.add(rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(Schedulers.io())
            .subscribe({
                // possibly new high or low mark
                resendDataToWatch()
                // status may be formatted differently
                sendDataToWatch(status = true, basals = false, bgValue = false)
            }) { fabricPrivacy.logException(it) })
        disposable.add(rxBus
            .toObservable(EventRefreshOverview::class.java)
            .observeOn(Schedulers.io())
            .subscribe({
                if (WatchUpdaterService.shouldReportLoopStatus(loopPlugin.get().isEnabled(PluginType.LOOP)) || TizenUpdaterService.shouldReportLoopStatus(loopPlugin.get().isEnabled(PluginType.LOOP)))
                    sendDataToWatch(status = true, basals = false, bgValue = false)
            }) { fabricPrivacy.logException(it) })
        disposable.add(rxBus
            .toObservable(EventBolusRequested::class.java)
            .observeOn(Schedulers.io())
            .subscribe({ event: EventBolusRequested ->
                val status = String.format(resourceHelper.gs(R.string.bolusrequested), event.amount)
                val intent = Intent(mainApp, WatchUpdaterService::class.java).setAction(WatchUpdaterService.ACTION_SEND_BOLUSPROGRESS)
                intent.putExtra("progresspercent", 0)
                intent.putExtra("progressstatus", status)
                mainApp.startService(intent)

                val intent2 = Intent(mainApp, TizenUpdaterService::class.java).setAction(TizenUpdaterService.ACTION_SEND_BOLUSPROGRESS)
                intent2.putExtra("progresspercent", 0)
                intent2.putExtra("progressstatus", status)
                mainApp.startService(intent2)
            }) { fabricPrivacy.logException(it) })
        disposable.add(rxBus
            .toObservable(EventDismissBolusProgressIfRunning::class.java)
            .observeOn(Schedulers.io())
            .subscribe({ event: EventDismissBolusProgressIfRunning ->
                if (event.result == null) return@subscribe
                val status: String = if (event.result.success) {
                    resourceHelper.gs(R.string.success)
                } else {
                    resourceHelper.gs(R.string.nosuccess)
                }
                val intent = Intent(mainApp, WatchUpdaterService::class.java).setAction(WatchUpdaterService.ACTION_SEND_BOLUSPROGRESS)
                intent.putExtra("progresspercent", 100)
                intent.putExtra("progressstatus", status)
                mainApp.startService(intent)

                val intent2 = Intent(mainApp, TizenUpdaterService::class.java).setAction(TizenUpdaterService.ACTION_SEND_BOLUSPROGRESS)
                intent.putExtra("progresspercent", 100)
                intent.putExtra("progressstatus", status)
                mainApp.startService(intent2)

            }) { fabricPrivacy.logException(it) })
        disposable.add(rxBus
            .toObservable(EventOverviewBolusProgress::class.java)
            .observeOn(Schedulers.io())
            .subscribe({ event: EventOverviewBolusProgress ->
                if (!event.isSMB() || sp.getBoolean("wear_notifySMB", true)) {
                    val intent = Intent(mainApp, WatchUpdaterService::class.java).setAction(WatchUpdaterService.ACTION_SEND_BOLUSPROGRESS)
                    intent.putExtra("progresspercent", event.percent)
                    intent.putExtra("progressstatus", event.status)
                    mainApp.startService(intent)

                    val intent2 = Intent(mainApp, TizenUpdaterService::class.java).setAction(TizenUpdaterService.ACTION_SEND_BOLUSPROGRESS)
                    intent2.putExtra("progresspercent", event.percent)
                    intent2.putExtra("progressstatus", event.status)
                    mainApp.startService(intent2)
                }
            }) { fabricPrivacy.logException(it) })
    }

    override fun onStop() {
        disposable.clear()
        //Todo disabled Check code in "onDestroy" function of TizenUpdaterService
        // mainApp.unbindService(mConnection)
        super.onStop()
    }


    private fun sendDataToWatch(status: Boolean, basals: Boolean, bgValue: Boolean) {
        //Log.d(TAG, "WR: WearPlugin:sendDataToWatch (status=" + status + ",basals=" + basals + ",bgValue=" + bgValue + ")");
        if (isEnabled(getType())) {
            // only start service when this plugin is enabled
            if (bgValue) {
                mainApp.startService(Intent(mainApp, WatchUpdaterService::class.java))
                mainApp.startService(Intent(mainApp, TizenUpdaterService::class.java))
            }
            if (basals) {
                mainApp.startService(Intent(mainApp, WatchUpdaterService::class.java).setAction(WatchUpdaterService.ACTION_SEND_BASALS))
                mainApp.startService(Intent(mainApp, TizenUpdaterService::class.java).setAction(TizenUpdaterService.ACTION_SEND_BASALS))
            }
            if (status) {
                mainApp.startService(Intent(mainApp, WatchUpdaterService::class.java).setAction(WatchUpdaterService.ACTION_SEND_STATUS))
                mainApp.startService(Intent(mainApp, TizenUpdaterService::class.java).setAction(TizenUpdaterService.ACTION_SEND_STATUS))
            }
        }
    }

    fun resendDataToWatch() {
        mainApp.startService(Intent(mainApp, WatchUpdaterService::class.java).setAction(WatchUpdaterService.ACTION_RESEND))
/* Todo code disabled (it was for activate or desactivate TizenUpdaterService according to settings, should be managed inside TizenUpdaterService
        //if preference changed, Start or Stop Tizen communication
        if (sp.getBoolean(TizenUpdaterService.TIZEN_ENABLE, false) ) {
            if (!mIsBound) {
                //Toast.makeText(mainApp, "Launch Tizen callback", Toast.LENGTH_LONG).show()
                mIsBound = mainApp.bindService(Intent(mainApp, TizenUpdaterService::class.java), mConnection, Context.BIND_AUTO_CREATE)
            } else {
                tizenUS!!.findPeers()
                Toast.makeText(mainApp, "Try to find peers", Toast.LENGTH_LONG).show()
            }
        } else {
            if (mIsBound == true && tizenUS != null) { tizenUS!!.closeConnection() }
        }
        */
        mainApp.startService(Intent(mainApp, TizenUpdaterService::class.java).setAction(TizenUpdaterService.ACTION_RESEND))
    }

    fun openSettings() {
        //Log.d(TAG, "WR: WearPlugin:openSettings");
        mainApp.startService(Intent(mainApp, WatchUpdaterService::class.java).setAction(WatchUpdaterService.ACTION_OPEN_SETTINGS))
        mainApp.startService(Intent(mainApp, TizenUpdaterService::class.java).setAction(TizenUpdaterService.ACTION_OPEN_SETTINGS))
    }

    fun requestNotificationCancel(actionString: String?) { //Log.d(TAG, "WR: WearPlugin:requestNotificationCancel");
        val intent = Intent(mainApp, WatchUpdaterService::class.java)
            .setAction(WatchUpdaterService.ACTION_CANCEL_NOTIFICATION)
        intent.putExtra("actionstring", actionString)
        mainApp.startService(intent)

        val intent2 = Intent(mainApp, TizenUpdaterService::class.java)
            .setAction(TizenUpdaterService.ACTION_CANCEL_NOTIFICATION)
        intent2.putExtra("actionstring", actionString)
        mainApp.startService(intent2)
    }

    fun requestActionConfirmation(title: String, message: String, actionString: String) {
        val intent = Intent(mainApp, WatchUpdaterService::class.java).setAction(WatchUpdaterService.ACTION_SEND_ACTIONCONFIRMATIONREQUEST)
        intent.putExtra("title", title)
        intent.putExtra("message", message)
        intent.putExtra("actionstring", actionString)
        mainApp.startService(intent)

        val intent2 = Intent(mainApp, TizenUpdaterService::class.java).setAction(TizenUpdaterService.ACTION_SEND_ACTIONCONFIRMATIONREQUEST)
        intent2.putExtra("title", title)
        intent2.putExtra("message", message)
        intent2.putExtra("actionstring", actionString)
        mainApp.startService(intent2)
    }

    fun requestChangeConfirmation(title: String, message: String, actionString: String) {
        val intent = Intent(mainApp, WatchUpdaterService::class.java).setAction(WatchUpdaterService.ACTION_SEND_CHANGECONFIRMATIONREQUEST)
        intent.putExtra("title", title)
        intent.putExtra("message", message)
        intent.putExtra("actionstring", actionString)
        mainApp.startService(intent)

        val intent2 = Intent(mainApp, TizenUpdaterService::class.java).setAction(TizenUpdaterService.ACTION_SEND_CHANGECONFIRMATIONREQUEST)
        intent2.putExtra("title", title)
        intent2.putExtra("message", message)
        intent2.putExtra("actionstring", actionString)
        mainApp.startService(intent2)
    }

/* Todo : code disabled, should be in TizenUpdaterService (inside or called by tizenApiConnect function)
    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            tizenUS = (service as TizenUpdaterService.LocalBinder).getService()
            //Toast.makeText(mainApp, "Try to find peers", Toast.LENGTH_LONG).show()
            //tizenUS!!.findPeers()
        }
        override fun onServiceDisconnected(className: ComponentName) {
            tizenUS = null
            mIsBound = false
            Toast.makeText(mainApp, "Service disconnected", Toast.LENGTH_LONG).show()
        }
    }

 */

}