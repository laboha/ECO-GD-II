package com.ecohimpribor.ecogdmobile.ui

import android.content.Context
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout

/**
 * Квадратная панель для фото газоанализатора.
 *
 * Раньше панель была прямоугольной с фиксированной высотой 200dp.
 * По требованию — экран отображения показаний должен быть КВАДРАТНЫМ,
 * а не прямоугольным. Так как панель лежит внутри вертикального
 * LinearLayout (а не напрямую в ConstraintLayout), обычный
 * app:layout_constraintDimensionRatio="1:1" не сработает — он учитывается
 * только когда родитель сам ConstraintLayout. Поэтому здесь высота
 * принудительно приравнивается к ширине в onMeasure(), что гарантированно
 * работает в любом родительском контейнере и на любом размере экрана.
 */
class SquareConstraintLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
