package me.rhunk.snapenhance.core.features.impl.ui

import android.content.res.Resources
import android.util.Size
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import me.rhunk.snapenhance.common.util.ktx.findFieldsToString
import me.rhunk.snapenhance.core.event.events.impl.AddViewEvent
import me.rhunk.snapenhance.core.event.events.impl.BindViewEvent
import me.rhunk.snapenhance.core.event.events.impl.LayoutInflateEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.ui.getComposerContext
import me.rhunk.snapenhance.core.util.dataBuilder
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.Hooker
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.ktx.getIdentifier

class UITweaks : Feature("UITweaks") {
    private val identifierCache = mutableMapOf<String, Int>()

    fun getId(name: String, defType: String): Int {
        return identifierCache.getOrPut("$name:$defType") {
            context.resources.getIdentifier(name, defType)
        }
    }

    private fun hideStorySection(event: AddViewEvent) {
        val parent = event.parent
        parent.visibility = View.GONE
        val marginLayoutParams = parent.layoutParams as MarginLayoutParams
        marginLayoutParams.setMargins(-99999, -99999, -99999, -99999)
        event.canceled = true
    }

    private fun hideView(view: View) {
        view.apply {
            visibility = View.GONE
            post {
                isEnabled = false
                visibility = View.GONE
                setWillNotDraw(true)
            }
            addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                view.post { view.visibility = View.GONE }
            }
        }
    }

    private fun onActivityCreate() {
        val blockAds by context.config.global.blockAds
        val hiddenElements by context.config.userInterface.hideUiComponents
        val hideStorySuggestions by context.config.userInterface.hideStorySuggestions
        val isImmersiveCamera by context.config.camera.immersiveCameraPreview

        val displayMetrics = context.resources.displayMetrics
        val deviceAspectRatio = displayMetrics.widthPixels.toFloat() / displayMetrics.heightPixels.toFloat()

        val callButtonsStub = getId("call_buttons_stub", "id")
        val callButton1 = getId("friend_action_button3", "id")
        val callButton2 = getId("friend_action_button4", "id")

        val chatNoteRecordButton = getId("chat_note_record_button", "id")
        val unreadHintButton = getId("unread_hint_button", "id")
        val friendCardFrame = getId("friend_card_frame", "id")

        View::class.java.hook("setVisibility", HookStage.BEFORE) { methodParam ->
            val viewId = (methodParam.thisObject() as View).id
            if (viewId == callButton1 || viewId == callButton2) {
                if (!hiddenElements.contains("hide_profile_call_buttons")) return@hook
                methodParam.setArg(0, View.GONE)
            }
        }

        context.event.subscribe(LayoutInflateEvent::class) { event ->
            if (event.layoutId == getId("chat_input_bar_sharing_drawer_button", "layout") && hiddenElements.contains("hide_live_location_share_button")) {
                hideView(event.view ?: return@subscribe)
            }
        }

        Resources::class.java.methods.first { it.name == "getDimensionPixelSize"}.hook(
            HookStage.AFTER,
            { isImmersiveCamera }
        ) { param ->
            val id = param.arg<Int>(0)
            if (id == getId("capri_viewfinder_default_corner_radius", "dimen") ||
                id == getId("ngs_hova_nav_larger_camera_button_size", "dimen")) {
                param.setResult(0)
            }
        }

        var friendCardFrameSize: Size? = null

        val fourDp by lazy {
            (4 * context.androidContext.resources.displayMetrics.density).toInt()
        }

        context.event.subscribe(BindViewEvent::class, { hideStorySuggestions.isNotEmpty() }) { event ->
            if (event.view is FrameLayout) {
                val viewModelString = event.prevModel.toString()
                val isSuggestedFriend by lazy { viewModelString.startsWith("DFFriendSuggestionCardViewModel") }
                val isMyStory by lazy { viewModelString.let { it.startsWith("CircularItemViewModel") && it.contains("storyId=")} }

                if ((hideStorySuggestions.contains("hide_friend_suggestions") && isSuggestedFriend) ||
                    (hideStorySuggestions.contains("hide_my_stories") && isMyStory)) {
                    event.view.layoutParams.apply {
                        width = 0; height = 0
                        if (this is MarginLayoutParams) setMargins(-fourDp, 0, -fourDp, 0)
                    }
                    return@subscribe
                }
            }

            if (event.view.id == friendCardFrame && hideStorySuggestions.contains("hide_suggested_friend_stories")) {
                val friendStoryData = event.prevModel::class.java.findFieldsToString(event.prevModel, once = true) { _, value ->
                    value.contains("FriendStoryData")
                }.firstOrNull()?.get(event.prevModel) ?: return@subscribe

                event.view.layoutParams.apply {
                    if (friendCardFrameSize == null && width > 0 && height > 0) {
                        friendCardFrameSize = Size(width, height)
                    }

                    if (friendStoryData.toString().contains("isFriendOfFriend=true")) {
                        width = 0
                        height = 0
                    } else {
                        friendCardFrameSize?.let {
                            width = it.width
                            height = it.height
                        }
                    }
                }
            }
        }

        context.event.subscribe(AddViewEvent::class) { event ->
            val viewId = event.view.id
            val view = event.view

            if (blockAds && viewId == getId("df_promoted_story", "id")) {
                hideStorySection(event)
            }

            if (isImmersiveCamera) {
                if (view.id == getId("edits_container", "id")) {
                    Hooker.hookObjectMethod(View::class.java, view, "layout", HookStage.BEFORE) {
                        val width = it.arg(2) as Int
                        val realHeight = (width / deviceAspectRatio).toInt()
                        it.setArg(3, realHeight)
                    }
                }
                if (view.id == getId("full_screen_surface_view", "id")) {
                    Hooker.hookObjectMethod(View::class.java, view, "layout", HookStage.BEFORE) {
                        it.setArg(1, 1)
                        it.setArg(3, displayMetrics.heightPixels)
                    }
                }
            }

            if (hiddenElements.contains("hide_billboard_prompt") && event.parent.javaClass.name.endsWith("BillboardFeedHeaderPromptComponent")) {
                hideView(event.parent)
                view.getComposerContext()?.componentContext?.get()?.dataBuilder {
                    val dismissFunction = get<Any>("_onDismiss") ?: return@subscribe
                    dismissFunction.javaClass.getMethod("invoke").invoke(dismissFunction)
                }
            }

            if (event.parent.id == getId("map_reactions_layout", "id") && hiddenElements.contains("hide_map_reactions")) {
                hideView(view)
            }

            if (
                ((viewId == getId("post_tool", "id") || viewId == getId("story_button", "id")) && hiddenElements.contains("hide_post_to_story_buttons")) ||
                (viewId == chatNoteRecordButton && hiddenElements.contains("hide_voice_record_button")) ||
                (viewId == getId("chat_input_bar_sticker", "id") && hiddenElements.contains("hide_stickers_button")) ||
                (viewId == getId("chat_input_bar_sharing_drawer_button", "id") && hiddenElements.contains("hide_live_location_share_button")) ||
                (viewId == callButtonsStub && hiddenElements.contains("hide_chat_call_buttons"))
            ) {
                hideView(view)
            }
            if (viewId == unreadHintButton && hiddenElements.contains("hide_unread_chat_hint")) {
                event.canceled = true
            }
        }
    }

    override fun init() {
        onNextActivityCreate {
            onActivityCreate()
        }
    }
}