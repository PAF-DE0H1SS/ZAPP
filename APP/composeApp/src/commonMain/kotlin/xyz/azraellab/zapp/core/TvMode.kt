package xyz.azraellab.zapp.core

/**
 * Множитель шрифта на TV: 10-foot UI читается с дивана, с трёх метров.
 *
 * Меняется только fontScale -- плотность пикселей остаётся той же, поэтому
 * отступы и размеры в dp не прыгают, а текст и иконки в sp растут.
 */
const val TV_FONT_SCALE = 1.3f

/** Итоговый fontScale: на TV усиливаем, на остальных -- системный. */
fun tvFontScale(base: Float, tvMode: Boolean): Float =
    if (tvMode) base * TV_FONT_SCALE else base
