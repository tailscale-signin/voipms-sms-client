package net.kourlas.voipms_sms.delivery

import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.conversation.ConversationActivity
import net.kourlas.voipms_sms.preferences.getDidShowInConversationsView
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.privacy.PrivateThreads
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.tools.LineProfiles
import net.kourlas.voipms_sms.utils.getContactName
import net.kourlas.voipms_sms.utils.getFormattedPhoneNumber
import java.text.DateFormat
import java.util.Date

internal class HomeCallFeed(private val activity: FragmentActivity, private val container: FrameLayout) {
    private var job: Job? = null
    private val date = DateFormat.getDateInstance(DateFormat.FULL)
    private val time = android.text.format.DateFormat.getTimeFormat(activity)
    private val prefs = DeliveryTrace.store(activity)
    private val empty = TextView(activity).apply { gravity=Gravity.CENTER; setPadding(activity.dp(20),activity.dp(32),activity.dp(20),activity.dp(32)) }
    private var count = 0
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith("callFeedError.") == true) updateEmpty()
    }
    private fun updateEmpty() {
        val generation = readAccountCredentials(activity)?.generation
        empty.setText(if (prefs.getBoolean("callFeedError.$generation",false)) R.string.calls_partial else R.string.calls_home_empty)
        empty.visibility=if (count==0 || prefs.getBoolean("callFeedError.$generation",false)) View.VISIBLE else View.GONE
    }

    fun show() {
        hide()
        val account = readAccountCredentials(activity)?.takeIf { it.configured }
        val layout = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        val header = LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(activity.dp(18),activity.dp(4),activity.dp(10),0) }
        header.addView(TextView(activity).apply { setText(R.string.calls_activity); textSize=20f; setTypeface(typeface,Typeface.BOLD) }, LinearLayout.LayoutParams(0,-2,1f))
        header.addView(MaterialButton(activity,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.delivery_refresh); setOnClickListener { CallFeedSync.refresh(activity,true) }
        })
        layout.addView(header); (empty.parent as? ViewGroup)?.removeView(empty); layout.addView(empty)
        val list = ListView(activity).apply { dividerHeight=0; clipToPadding=false; setPadding(0,0,0,activity.dp(28)) }
        layout.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        container.removeAllViews(); container.addView(layout)
        count=0; updateEmpty()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        if (account == null) return
        CallFeedSync.refresh(activity)
        job=activity.lifecycleScope.launch {
            CallFeedStore.observe(activity).collect { items ->
                val rows = withContext(Dispatchers.IO) {
                    val names=mutableMapOf<String,String>()
                    val active = net.kourlas.voipms_sms.preferences.getDids(activity, onlyRetrieveMessages = true)
                    items.filter { item ->
                        val lines = if (item.did.isNotEmpty()) setOf(item.did) else
                            prefs.getStringSet("mailboxLines.${account.generation}.${item.mailbox}", emptySet()).orEmpty()
                        lines.isNotEmpty() && lines.all { line -> line in active && getDidShowInConversationsView(activity,line) &&
                            !PrivateThreads.isPrivate(activity,ConversationId(line,item.peer)) }
                    }.map { item ->
                        item to if(item.peer.isEmpty()) activity.getString(R.string.calls_unknown)
                        else getContactName(activity,item.peer,names) ?: getFormattedPhoneNumber(item.peer)
                    }
                }
                if (readAccountCredentials(activity)?.generation != account.generation) return@collect
                val pos=list.firstVisiblePosition; val offset=list.getChildAt(0)?.top ?: 0
                count=rows.size; updateEmpty()
                list.adapter=object:BaseAdapter() {
                    override fun getCount()=rows.size
                    override fun getItem(position:Int)=rows[position].first
                    override fun getItemId(position:Int)=getItem(position).id.hashCode().toLong()
                    override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
                        val (event,name)=rows[position]
                        val day=if (event.voicemail && event.providerDate.length == 10) event.providerDate else date.format(Date(event.receivedAt))
                        val group=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(activity.dp(18),activity.dp(7),activity.dp(18),activity.dp(7)) }
                        val prior = if (position > 0) getItem(position - 1) else null
                        val priorDay = prior?.let { if (it.voicemail && it.providerDate.length == 10) it.providerDate else date.format(Date(it.receivedAt)) }
                        if(day != priorDay) group.addView(TextView(activity).apply {
                            text=day; textSize=13f; setTypeface(typeface,Typeface.BOLD); setPadding(0,activity.dp(8),0,activity.dp(8))
                        })
                        val card=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(activity.dp(12),activity.dp(10),activity.dp(12),0); setBackgroundResource(R.drawable.call_event_surface) }
                        val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
                        row.addView(ImageView(activity).apply { setImageResource(if(event.voicemail) R.drawable.ic_vm_mail else R.drawable.ic_vm_missed); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }, activity.dp(28), activity.dp(28))
                        val label=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(activity.dp(10),0,activity.dp(6),0) }
                        label.addView(TextView(activity).apply { text=name; textSize=16f; setTypeface(typeface,Typeface.BOLD) })
                        val line=if(event.did.isEmpty()) activity.getString(R.string.calls_shared) else LineProfiles.label(activity,event.did)
                        label.addView(TextView(activity).apply { text=activity.getString(R.string.calls_row_detail,activity.getString(if(event.voicemail) R.string.calls_voicemail else R.string.calls_missed),line);textSize=13f })
                        row.addView(label,LinearLayout.LayoutParams(0,-2,1f))
                        row.addView(TextView(activity).apply { text=if (event.voicemail && event.providerDate.length == 10) "" else time.format(Date(event.receivedAt)); textSize=13f })
                        card.addView(row); if(event.voicemail) card.addView(CallPlayback.controls(activity,event))
                        if(event.did.isNotEmpty() && event.peer.isNotEmpty()) card.addView(MaterialButton(activity,null,androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                            setText(R.string.calls_reply); gravity=Gravity.CENTER_VERTICAL or Gravity.START; isAllCaps=false
                            setOnClickListener { if(readAccountCredentials(activity)?.generation==account.generation)
                                activity.startActivity(Intent(activity,ConversationActivity::class.java)
                                    .putExtra(activity.getString(R.string.conversation_did),event.did)
                                    .putExtra(activity.getString(R.string.conversation_contact),event.peer)) }
                        }) else card.setPadding(activity.dp(12),activity.dp(10),activity.dp(12),activity.dp(10))
                        group.addView(card); return group
                    }
                }
                if (pos>0) list.setSelectionFromTop(pos,offset)
            }
        }
    }
    fun hide() {
        job?.cancel(); job=null
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        if (container.childCount > 0) {
            ViewModelProvider(activity)[VoicePlaybackModel::class.java].stop()
            container.removeAllViews()
        }
    }
}
