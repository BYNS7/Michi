package com.michi.arana

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarRenderTest {
    @Test fun render() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cell = 300
        val shots = listOf(
            AvatarView.Skin.SOLDADO to AvatarView.Move.IDLE, AvatarView.Skin.SOLDADO to AvatarView.Move.AIM,
            AvatarView.Skin.SOLDADO to AvatarView.Move.SALUTE, AvatarView.Skin.SOLDADO to AvatarView.Move.WALK,
            AvatarView.Skin.ARANA_ROJA to AvatarView.Move.IDLE, AvatarView.Skin.ARANA_ROJA to AvatarView.Move.SWING,
            AvatarView.Skin.ARANA_ROJA to AvatarView.Move.WEB, AvatarView.Skin.ARANA_ROJA to AvatarView.Move.PARTY,
            AvatarView.Skin.ARANA_NEGRA to AvatarView.Move.IDLE, AvatarView.Skin.ARANA_NEGRA to AvatarView.Move.WAVE,
            AvatarView.Skin.ARANA_NEGRA to AvatarView.Move.DANCE, AvatarView.Skin.ARANA_NEGRA to AvatarView.Move.TRANSFORM,
        )
        val out = Bitmap.createBitmap(cell * 4, cell * 3, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out); oc.drawColor(Color.parseColor("#CFE6F5"))
        shots.forEachIndexed { i, (skin, move) ->
            val v = AvatarView(ctx)
            v.setSkinNow(skin); v.facing = -1
            if (move == AvatarView.Move.TRANSFORM) v.transformTo(AvatarView.Skin.SOLDADO) else v.play(move)
            ShadowSystemClock.advanceBy(Duration.ofMillis(if (move == AvatarView.Move.TRANSFORM) 300 else 700))
            v.measure(cell, cell); v.layout(0, 0, cell, cell)
            oc.save(); oc.translate(((i % 4) * cell).toFloat(), ((i / 4) * cell).toFloat()); v.draw(oc); oc.restore()
        }
        val f = File("build/avatars/avatars.png"); f.parentFile.mkdirs()
        FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("WROTE " + f)
    }
}
