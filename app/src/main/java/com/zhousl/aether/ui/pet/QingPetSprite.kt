package com.zhousl.aether.ui.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt

/**
 * Qing built-in pet atlas loader (8x11 WebP: rows 0-8 animation states,
 * rows 9-10 are 16 look directions). Multi-pet aware via asset path.
 */
class QingPetSpriteSheet private constructor(
    val image: ImageBitmap,
    val columns: Int,
    val rows: Int,
    val cellWidth: Int,
    val cellHeight: Int,
) {
    companion object {
        private const val CELL_COLUMNS = 8
        private const val CELL_ROWS = 11
        private const val CELL_WIDTH = 192
        private const val CELL_HEIGHT = 208

        fun load(
            context: Context,
            assetPath: String = QingPetCatalog.byId(QingPetCatalog.DefaultPetId).spriteAssetPath,
        ): QingPetSpriteSheet? = runCatching {
            val bitmap = context.assets.open(assetPath).use { input ->
                BitmapFactory.decodeStream(input)
            } ?: return null
            val downscaled = maybeDownscale(bitmap)
            QingPetSpriteSheet(
                image = downscaled.asImageBitmap(),
                columns = CELL_COLUMNS,
                rows = CELL_ROWS,
                cellWidth = downscaled.width / CELL_COLUMNS,
                cellHeight = downscaled.height / CELL_ROWS,
            )
        }.getOrNull()

        /**
         * Avoid holding a huge bitmap in memory: downscale the whole atlas so each cell is
         * about 156px tall before slicing frames at draw time. 156 keeps 1536x2288 -> 1152x1716,
         * so both cell dimensions divide exactly (144x156) and frames do not drift across columns.
         */
        private fun maybeDownscale(bitmap: Bitmap): Bitmap {
            val targetCellHeight = 156
            if (bitmap.height / CELL_ROWS <= targetCellHeight) return bitmap
            val scale = targetCellHeight.toFloat() / (bitmap.height / CELL_ROWS)
            val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        }
    }
}

enum class QingPetMood {
    Idle,
    Waiting,
    Running,
    RunningLeft,
    RunningRight,
    Review,
    Jumping,
    Failed,
    Waving,
}

data class PetMoodSpec(
    val row: Int,
    val frameCount: Int,
    val frameDurationMillis: Int,
    val loop: Boolean,
)

fun petMoodSpec(mood: QingPetMood): PetMoodSpec = when (mood) {
    QingPetMood.Idle -> PetMoodSpec(row = 0, frameCount = 6, frameDurationMillis = 220, loop = true)
    QingPetMood.Waiting -> PetMoodSpec(row = 6, frameCount = 6, frameDurationMillis = 180, loop = true)
    QingPetMood.Running -> PetMoodSpec(row = 7, frameCount = 6, frameDurationMillis = 120, loop = true)
    QingPetMood.RunningLeft -> PetMoodSpec(row = 2, frameCount = 8, frameDurationMillis = 110, loop = true)
    QingPetMood.RunningRight -> PetMoodSpec(row = 1, frameCount = 7, frameDurationMillis = 110, loop = true)
    QingPetMood.Review -> PetMoodSpec(row = 8, frameCount = 6, frameDurationMillis = 160, loop = true)
    QingPetMood.Jumping -> PetMoodSpec(row = 4, frameCount = 5, frameDurationMillis = 140, loop = false)
    QingPetMood.Failed -> PetMoodSpec(row = 5, frameCount = 8, frameDurationMillis = 160, loop = false)
    QingPetMood.Waving -> PetMoodSpec(row = 3, frameCount = 4, frameDurationMillis = 160, loop = false)
}

/**
 * Maps a look angle (degrees, 0 = right, 90 = down, clockwise on screen) to the
 * 16-direction look cells in rows 9-10 of the extended atlas.
 */
fun petLookCell(angleDegrees: Float): Pair<Int, Int> {
    val normalized = ((angleDegrees % 360f) + 360f) % 360f
    val column = ((normalized / 22.5f).roundToInt() + 16) % 16
    return if (column < 8) 9 to column else 10 to (column - 8)
}
