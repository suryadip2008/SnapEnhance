package me.rhunk.snapenhance.core.features.impl.global

import android.os.Build
import android.os.FileObserver
import com.google.gson.JsonParser
import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.util.dataBuilder
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hookConstructor
import me.rhunk.snapenhance.core.util.ktx.setObjectField
import me.rhunk.snapenhance.mapper.impl.DefaultMediaItemMapper
import java.io.File

class BypassVideoLengthRestriction :
    Feature("BypassVideoLengthRestriction") {
    private lateinit var fileObserver: FileObserver

    override fun init() {
        onNextActivityCreate(defer = true) {
            val mode = context.config.global.bypassVideoLengthRestriction.getNullable()

            if (mode == "single") {
                //fix black videos when story is posted
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val postedStorySnapFolder =
                        File(context.androidContext.filesDir, "file_manager/posted_story_snap")

                    fileObserver = (object : FileObserver(postedStorySnapFolder, MOVED_TO) {
                        override fun onEvent(event: Int, path: String?) {
                            if (event != MOVED_TO || path?.endsWith("posted_story_snap.2") != true) return
                            fileObserver.stopWatching()

                            val file = File(postedStorySnapFolder, path)
                            runCatching {
                                val fileContent = JsonParser.parseReader(file.reader()).asJsonObject
                                if (fileContent["timerOrDuration"].asLong < 0) file.delete()
                            }.onFailure {
                                context.log.error("Failed to read story metadata file", it)
                            }
                        }
                    })

                    context.event.subscribe(SendMessageWithContentEvent::class) { event ->
                        if (event.destinations.stories!!.isEmpty()) return@subscribe
                        fileObserver.startWatching()
                    }
                }

                context.mappings.useMapper(DefaultMediaItemMapper::class) {
                    defaultMediaItemClass.getAsClass()?.hookConstructor(HookStage.AFTER) { param ->
                        //set the video length argument
                        param.thisObject<Any>().dataBuilder {
                            set(defaultMediaItemDurationMsField.getAsString()!!, -1L)
                        }
                    }
                }
            }

            //TODO: allow split from any source
            if (mode == "split") {
                // memories grid
                context.mappings.useMapper(DefaultMediaItemMapper::class) {
                    cameraRollMediaId.getAsClass()?.hookConstructor(HookStage.AFTER) { param ->
                        //set the durationMs field
                        param.thisObject<Any>()
                            .setObjectField(durationMsField.get()!!, -1L)
                    }
                }

                // chat camera roll grid
                findClass("com.snap.composer.memories.MemoriesPickerVideoDurationConfig").hookConstructor(HookStage.AFTER) { param ->
                    param.thisObject<Any>().apply {
                        setObjectField("_maxSingleItemDurationMs", null)
                        setObjectField("_maxTotalDurationMs", null)
                    }
                }
            }
        }
    }
}