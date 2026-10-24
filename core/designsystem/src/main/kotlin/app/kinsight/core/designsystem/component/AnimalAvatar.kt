package app.kinsight.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kinsight.core.model.Animal
import app.kinsight.core.model.Mood

/**
 * A family member's mascot (animal avatars spec, decisions 1, 2, 6): a flat animal face filling a circle on the
 * animal's own background, wearing the expression of [mood]. Drawn in code on a 100×100 grid scaled to [size], so it
 * reads the same at 32 dp and at 96 dp. [animal] null (not assigned yet) is a plain grey disc. Decorative: the name is
 * always written next to it, and the card that knows the mood says it in its own description.
 */
@Composable
fun AnimalAvatar(animal: Animal?, mood: Mood = Mood.CONTENT, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val k = this.size.minDimension / GRID
        val disc = Path().apply { addOval(Rect(0f, 0f, this@Canvas.size.minDimension, this@Canvas.size.minDimension)) }
        clipPath(disc) {
            scale(k, k, pivot = Offset.Zero) {
                if (animal == null) {
                    drawCircle(PlaceholderBackground, radius = 50f, center = Offset(50f, 50f))
                    drawCircle(PlaceholderHead, radius = 18f, center = Offset(50f, 40f))
                    drawOval(PlaceholderHead, topLeft = Offset(20f, 64f), size = Size(60f, 50f))
                } else {
                    drawRect(backgroundOf(animal), topLeft = Offset.Zero, size = Size(GRID, GRID))
                    val face = drawAnimal(animal)
                    drawMood(face, mood)
                }
            }
        }
    }
}

/** The background of [animal]'s disc; white features keep at least 3:1 on every one (AnimalAvatarTest). */
fun backgroundOf(animal: Animal): Color = when (animal) {
    Animal.CAT -> CatBackground
    Animal.DOG -> DogBackground
    Animal.FOX -> FoxBackground
    Animal.PANDA -> PandaBackground
    Animal.RABBIT -> RabbitBackground
    Animal.BEAR -> BearBackground
    Animal.OWL -> OwlBackground
    Animal.PENGUIN -> PenguinBackground
    Animal.FROG -> FrogBackground
    Animal.RACCOON -> RaccoonBackground
}

private const val GRID = 100f

// Backgrounds: mid tones around the app's violet, one hue per animal.
internal val CatBackground = Color(0xFF1E8C7E)
internal val DogBackground = Color(0xFFC9563F)
internal val FoxBackground = Color(0xFF2F6DB0)
internal val PandaBackground = Color(0xFF4F8A2A)
internal val RabbitBackground = Color(0xFFC74B7E)
internal val BearBackground = Color(0xFF7A5CD6)
internal val OwlBackground = Color(0xFF34508F)
internal val PenguinBackground = Color(0xFF2F8FC0)
internal val FrogBackground = Color(0xFF9C4FB3)
internal val RaccoonBackground = Color(0xFFC06A18)

private val PlaceholderBackground = Color(0xFFB9B5CC)
private val PlaceholderHead = Color(0xFFDCD7EC)

// Fur, skin and feathers.
private val CatFur = Color(0xFFFFB866)
private val CatStripe = Color(0xFFE88A2E)
private val PinkInner = Color(0xFFFF9FB4)
private val Nose = Color(0xFFF07A92)
private val DogFur = Color(0xFFE9BE86)
private val DogEar = Color(0xFF8A5634)
private val Cream = Color(0xFFFFF4E2)
private val FoxFur = Color(0xFFF2782C)
private val FoxTip = Color(0xFF4A2E28)
private val White = Color(0xFFFFFFFF)
private val PandaBlack = Color(0xFF2E2B38)
private val RabbitFur = Color(0xFFF7F3FB)
private val BearFur = Color(0xFF9A6440)
private val BearInner = Color(0xFFC99068)
private val BearMuzzle = Color(0xFFEBCDA6)
private val OwlFeather = Color(0xFF8D6A55)
private val OwlTuft = Color(0xFFB08A6C)
private val OwlDisc = Color(0xFFFFF2D8)
private val Beak = Color(0xFFFFB547)
private val PenguinBlack = Color(0xFF262A40)
private val FrogSkin = Color(0xFF7CCB55)
private val FrogDark = Color(0xFF5AA83A)
private val RaccoonFur = Color(0xFFAEABBD)
private val RaccoonDark = Color(0xFF3D3A4A)
private val RaccoonStripe = Color(0xFF8A8799)

// The expression layer.
private val Ink = Color(0xFF2B2440)
private val Blush = Color(0xFFFF7F9E)
private val Tongue = Color(0xFFFF8A8A)
private val Tear = Color(0xFF6CC4FF)
private val SickTint = Color(0xFFA5D63A)

/** Where [drawMood] puts the eyes and mouth of a face, on the 100×100 grid. */
private data class Face(
    val eyeY: Float,
    val eyeDx: Float,
    val mouthY: Float,
    val eyeColor: Color = Ink,
    val mouthWidth: Float = 1f,
    val cheekDx: Float = eyeDx + 8f,
)

private fun DrawScope.drawAnimal(animal: Animal): Face = when (animal) {
    Animal.CAT -> cat()
    Animal.DOG -> dog()
    Animal.FOX -> fox()
    Animal.PANDA -> panda()
    Animal.RABBIT -> rabbit()
    Animal.BEAR -> bear()
    Animal.OWL -> owl()
    Animal.PENGUIN -> penguin()
    Animal.FROG -> frog()
    Animal.RACCOON -> raccoon()
}

private fun polygon(vararg xy: Float): Path = Path().apply {
    moveTo(xy[0], xy[1])
    var i = 2
    while (i < xy.size) { lineTo(xy[i], xy[i + 1]); i += 2 }
    close()
}

/** A polygon with its corners rounded by a stroke of the same colour. */
private fun DrawScope.soft(color: Color, round: Float, vararg xy: Float) {
    val p = polygon(*xy)
    drawPath(p, color)
    drawPath(p, color, style = Stroke(width = round, join = StrokeJoin.Round))
}

/** [block] twice: as written for the left side and mirrored about x = 50 for the right. */
private inline fun DrawScope.mirrored(block: DrawScope.() -> Unit) {
    block()
    scale(-1f, 1f, pivot = Offset(50f, 50f)) { block() }
}

private fun DrawScope.oval(color: Color, cx: Float, cy: Float, w: Float, h: Float) =
    drawOval(color, topLeft = Offset(cx - w / 2, cy - h / 2), size = Size(w, h))

private fun DrawScope.cat(): Face {
    mirrored {
        soft(CatFur, 8f, 16f, 50f, 22f, 12f, 48f, 30f)
        soft(PinkInner, 4f, 24f, 40f, 26f, 22f, 40f, 32f)
    }
    oval(CatFur, 50f, 60f, 76f, 64f)
    drawLine(CatStripe, Offset(50f, 30f), Offset(50f, 38f), strokeWidth = 4f, cap = StrokeCap.Round)
    drawLine(CatStripe, Offset(42f, 31f), Offset(43.5f, 37f), strokeWidth = 4f, cap = StrokeCap.Round)
    drawLine(CatStripe, Offset(58f, 31f), Offset(56.5f, 37f), strokeWidth = 4f, cap = StrokeCap.Round)
    oval(Cream, 43f, 68f, 16f, 12f)
    oval(Cream, 57f, 68f, 16f, 12f)
    drawPath(polygon(46f, 63f, 54f, 63f, 50f, 67.5f), Nose)
    drawPath(polygon(46f, 63f, 54f, 63f, 50f, 67.5f), Nose, style = Stroke(2f, join = StrokeJoin.Round))
    mirrored {
        drawLine(Ink.copy(alpha = 0.55f), Offset(30f, 66f), Offset(10f, 62f), strokeWidth = 2f, cap = StrokeCap.Round)
        drawLine(Ink.copy(alpha = 0.55f), Offset(30f, 71f), Offset(11f, 73f), strokeWidth = 2f, cap = StrokeCap.Round)
    }
    return Face(eyeY = 53f, eyeDx = 13f, mouthY = 70f)
}

private fun DrawScope.dog(): Face {
    oval(DogFur, 50f, 58f, 64f, 68f)
    mirrored {
        rotate(18f, pivot = Offset(22f, 30f)) { oval(DogEar, 20f, 48f, 20f, 42f) }
    }
    oval(Cream, 50f, 74f, 36f, 26f)
    oval(PandaBlack, 50f, 65f, 13f, 9f)
    oval(White.copy(alpha = 0.6f), 47f, 63.5f, 4f, 2.5f)
    return Face(eyeY = 50f, eyeDx = 12f, mouthY = 74f, cheekDx = 21f)
}

private fun DrawScope.fox(): Face {
    mirrored {
        soft(FoxFur, 6f, 14f, 48f, 18f, 8f, 46f, 28f)
        soft(FoxTip, 3f, 21f, 38f, 21f, 18f, 37f, 29f)
    }
    val head = Path().apply {
        moveTo(50f, 25f)
        cubicTo(74f, 25f, 90f, 42f, 90f, 58f)
        cubicTo(90f, 74f, 66f, 90f, 50f, 92f)
        cubicTo(34f, 90f, 10f, 74f, 10f, 58f)
        cubicTo(10f, 42f, 26f, 25f, 50f, 25f)
        close()
    }
    drawPath(head, FoxFur)
    val cheeks = Path().apply {
        moveTo(10f, 58f)
        quadraticTo(32f, 56f, 50f, 64f)
        quadraticTo(68f, 56f, 90f, 58f)
        cubicTo(88f, 76f, 66f, 90f, 50f, 92f)
        cubicTo(34f, 90f, 12f, 76f, 10f, 58f)
        close()
    }
    drawPath(cheeks, Cream)
    oval(PandaBlack, 50f, 67f, 10f, 7f)
    return Face(eyeY = 49f, eyeDx = 14f, mouthY = 74f, cheekDx = 24f)
}

private fun DrawScope.panda(): Face {
    drawCircle(PandaBlack, radius = 12f, center = Offset(23f, 28f))
    drawCircle(PandaBlack, radius = 12f, center = Offset(77f, 28f))
    drawCircle(White, radius = 36f, center = Offset(50f, 59f))
    mirrored {
        rotate(-35f, pivot = Offset(36f, 54f)) { oval(PandaBlack, 36f, 54f, 21f, 15f) }
    }
    oval(PandaBlack, 50f, 66f, 10f, 7f)
    return Face(eyeY = 53f, eyeDx = 14f, mouthY = 72f, eyeColor = White, cheekDx = 25f)
}

private fun DrawScope.rabbit(): Face {
    mirrored {
        rotate(-12f, pivot = Offset(38f, 46f)) {
            oval(RabbitFur, 37f, 22f, 18f, 52f)
            oval(PinkInner, 37f, 24f, 9f, 40f)
        }
    }
    drawCircle(RabbitFur, radius = 32f, center = Offset(50f, 64f))
    oval(Nose, 50f, 67f, 7f, 5f)
    return Face(eyeY = 58f, eyeDx = 12f, mouthY = 74f, cheekDx = 21f)
}

private fun DrawScope.bear(): Face {
    mirrored {
        drawCircle(BearFur, radius = 13f, center = Offset(24f, 30f))
        drawCircle(BearInner, radius = 7f, center = Offset(24f, 30f))
    }
    drawCircle(BearFur, radius = 35f, center = Offset(50f, 59f))
    oval(BearMuzzle, 50f, 72f, 30f, 22f)
    oval(PandaBlack, 50f, 65f, 11f, 8f)
    return Face(eyeY = 51f, eyeDx = 14f, mouthY = 75f, cheekDx = 23f)
}

private fun DrawScope.owl(): Face {
    mirrored { soft(OwlTuft, 5f, 18f, 40f, 16f, 14f, 38f, 28f) }
    oval(OwlFeather, 50f, 62f, 72f, 76f)
    mirrored { drawCircle(OwlDisc, radius = 15f, center = Offset(35f, 52f)) }
    soft(Beak, 3f, 45f, 61f, 55f, 61f, 50f, 70f)
    return Face(eyeY = 52f, eyeDx = 15f, mouthY = 77f, mouthWidth = 0.8f, cheekDx = 26f)
}

private fun DrawScope.penguin(): Face {
    drawCircle(PenguinBlack, radius = 40f, center = Offset(50f, 58f))
    mirrored { drawCircle(White, radius = 19f, center = Offset(38f, 57f)) }
    oval(White, 50f, 74f, 52f, 36f)
    soft(Beak, 3f, 44f, 62f, 56f, 62f, 50f, 70f)
    return Face(eyeY = 53f, eyeDx = 12f, mouthY = 76f, mouthWidth = 0.8f, cheekDx = 21f)
}

private fun DrawScope.frog(): Face {
    mirrored { drawCircle(FrogSkin, radius = 15f, center = Offset(31f, 38f)) }
    oval(FrogSkin, 50f, 66f, 82f, 56f)
    mirrored { drawCircle(White, radius = 9.5f, center = Offset(31f, 38f)) }
    mirrored { drawCircle(FrogDark, radius = 1.5f, center = Offset(46f, 62f)) }
    return Face(eyeY = 38f, eyeDx = 19f, mouthY = 70f, mouthWidth = 1.4f, cheekDx = 28f)
}

private fun DrawScope.raccoon(): Face {
    mirrored {
        soft(RaccoonFur, 8f, 16f, 48f, 22f, 14f, 46f, 30f)
        soft(RaccoonDark, 4f, 23f, 40f, 25f, 23f, 38f, 32f)
    }
    drawCircle(RaccoonFur, radius = 35f, center = Offset(50f, 59f))
    drawLine(RaccoonStripe, Offset(50f, 27f), Offset(50f, 44f), strokeWidth = 6f, cap = StrokeCap.Round)
    mirrored { rotate(-10f, pivot = Offset(36f, 41f)) { oval(White, 36f, 41f, 18f, 6f) } }
    val mask = Path().apply {
        moveTo(14f, 52f)
        quadraticTo(30f, 42f, 50f, 50f)
        quadraticTo(70f, 42f, 86f, 52f)
        quadraticTo(84f, 64f, 70f, 63f)
        quadraticTo(58f, 62f, 50f, 58f)
        quadraticTo(42f, 62f, 30f, 63f)
        quadraticTo(16f, 64f, 14f, 52f)
        close()
    }
    drawPath(mask, RaccoonDark)
    oval(White, 50f, 73f, 30f, 22f)
    oval(PandaBlack, 50f, 65f, 10f, 7f)
    return Face(eyeY = 54f, eyeDx = 14f, mouthY = 75f, eyeColor = White, cheekDx = 24f)
}

/** A drop with its point up, its round bottom centred at ([cx], [cy]). */
private fun drop(cx: Float, cy: Float, r: Float): Path = Path().apply {
    moveTo(cx, cy - 2.3f * r)
    cubicTo(cx + 0.5f * r, cy - 1.4f * r, cx + r, cy - 0.7f * r, cx + r, cy)
    arcTo(Rect(cx - r, cy - r, cx + r, cy + r), 0f, 180f, false)
    cubicTo(cx - r, cy - 0.7f * r, cx - 0.5f * r, cy - 1.4f * r, cx, cy - 2.3f * r)
    close()
}

private fun DrawScope.drawMood(face: Face, mood: Mood) {
    val lx = 50f - face.eyeDx
    val rx = 50f + face.eyeDx
    val ey = face.eyeY
    val mx = 50f
    val my = face.mouthY
    val w = face.mouthWidth
    val line = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun dotEyes(r: Float = 4.4f) {
        for (x in listOf(lx, rx)) {
            drawCircle(face.eyeColor, radius = r, center = Offset(x, ey))
            if (face.eyeColor == Ink) drawCircle(White, radius = r * 0.32f, center = Offset(x - r * 0.35f, ey - r * 0.35f))
        }
    }
    fun cheeks() {
        for (x in listOf(50f - face.cheekDx, 50f + face.cheekDx)) oval(Blush.copy(alpha = 0.6f), x, ey + 9f, 10f, 6f)
    }
    when (mood) {
        Mood.HAPPY -> {
            cheeks()
            for (x in listOf(lx, rx)) {
                drawPath(Path().apply { moveTo(x - 5f, ey + 2f); quadraticTo(x, ey - 7f, x + 5f, ey + 2f) }, face.eyeColor, style = line)
            }
            val open = Path().apply {
                moveTo(mx - 9f * w, my - 2f)
                quadraticTo(mx, my - 0.5f, mx + 9f * w, my - 2f)
                quadraticTo(mx + 8f * w, my + 11f, mx, my + 11f)
                quadraticTo(mx - 8f * w, my + 11f, mx - 9f * w, my - 2f)
                close()
            }
            drawPath(open, Ink)
            drawPath(open, Ink, style = Stroke(2f, join = StrokeJoin.Round))
            clipPath(open) { oval(Tongue, mx, my + 10f, 10f * w, 8f) }
        }
        Mood.CONTENT -> {
            dotEyes()
            drawPath(Path().apply { moveTo(mx - 7f * w, my - 1f); quadraticTo(mx, my + 8f, mx + 7f * w, my - 1f) }, Ink, style = line)
        }
        Mood.CALM -> {
            dotEyes(3.8f)
            drawLine(Ink, Offset(mx - 6f * w, my + 2f), Offset(mx + 6f * w, my + 2f), strokeWidth = line.width, cap = StrokeCap.Round)
        }
        Mood.WORRIED -> {
            dotEyes()
            drawLine(Ink, Offset(lx - 5f, ey - 8f), Offset(lx + 4f, ey - 12f), strokeWidth = 3.5f, cap = StrokeCap.Round)
            drawLine(Ink, Offset(rx + 5f, ey - 8f), Offset(rx - 4f, ey - 12f), strokeWidth = 3.5f, cap = StrokeCap.Round)
            val s = 3.2f * w
            drawPath(
                Path().apply {
                    moveTo(mx - 3 * s, my + 2f)
                    quadraticTo(mx - 2.5f * s, my - 1.5f, mx - 1.5f * s, my + 1f)
                    quadraticTo(mx - 0.75f * s, my + 3.5f, mx, my + 1f)
                    quadraticTo(mx + 0.75f * s, my - 1.5f, mx + 1.5f * s, my + 1f)
                    quadraticTo(mx + 2.5f * s, my + 3.5f, mx + 3 * s, my + 1f)
                },
                Ink, style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        Mood.UPSET -> {
            dotEyes(4.8f)
            drawLine(Ink, Offset(lx - 6f, ey - 6f), Offset(lx + 4f, ey - 10f), strokeWidth = 3.5f, cap = StrokeCap.Round)
            drawLine(Ink, Offset(rx + 6f, ey - 6f), Offset(rx - 4f, ey - 10f), strokeWidth = 3.5f, cap = StrokeCap.Round)
            drawPath(Path().apply { moveTo(mx - 7f * w, my + 6f); quadraticTo(mx, my - 3f, mx + 7f * w, my + 6f) }, Ink, style = line)
            val t = drop(rx + 1f, ey + 12f, 4.2f)
            drawPath(t, Tear)
            drawPath(t, White, style = Stroke(1.2f))
        }
        Mood.UNWELL -> {
            val c = Offset(50f, (ey + my) / 2f)
            drawCircle(
                Brush.radialGradient(
                    0f to SickTint.copy(alpha = 0.55f), 0.65f to SickTint.copy(alpha = 0.45f), 1f to SickTint.copy(alpha = 0f),
                    center = c, radius = 34f,
                ),
                radius = 34f, center = c,
            )
            // Squeezed-shut eyes, > and <.
            drawPath(Path().apply { moveTo(lx - 4.5f, ey - 4f); lineTo(lx + 3.5f, ey); lineTo(lx - 4.5f, ey + 4f) }, face.eyeColor, style = line)
            drawPath(Path().apply { moveTo(rx + 4.5f, ey - 4f); lineTo(rx - 3.5f, ey); lineTo(rx + 4.5f, ey + 4f) }, face.eyeColor, style = line)
            drawPath(
                Path().apply { moveTo(mx - 8f * w, my + 7f); quadraticTo(mx, my - 6f, mx + 8f * w, my + 7f) },
                Ink, style = Stroke(width = 4.5f, cap = StrokeCap.Round),
            )
            for (x in listOf(lx - 2f, rx + 2f)) {
                val t = drop(x, ey + 11f, 3.2f)
                drawPath(t, Tear)
                drawPath(t, White, style = Stroke(1.2f))
            }
            val sweat = drop(78f, 30f, 4.5f)
            drawPath(sweat, Tear)
            drawPath(sweat, White, style = Stroke(1.4f))
        }
    }
}
