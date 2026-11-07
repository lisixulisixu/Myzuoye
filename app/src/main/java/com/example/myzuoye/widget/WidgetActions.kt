package com.example.myzuoye.widget

/**
 * 小组件 → 应用的直达动作协议。
 *
 * 单独抽一个对象，而不是塞进 MainActivity 的 companion：
 * 那边整个 companion 是 private 的（装的都是界面内部常量），小组件渲染侧根本读不到。
 * 这里的三个值是两个模块之间的公共契约，放在独立文件里归属更清楚。
 *
 * 用 extra 而不是自定义 action 的原因：action 需要在清单里声明 intent-filter
 * 才不会被系统「找不到 Activity」拦掉，而 extra 是同一组件内的约定，没有外部依赖。
 */
object WidgetActions {

    /** 点击时携带的动作 key */
    const val EXTRA_ACTION = "com.example.myzuoye.extra.WIDGET_ACTION"

    /** 直接拉起系统文件选择器 */
    const val SEND = "send"

    /** 直接开相册选二维码 */
    const val SCAN = "scan"

    /** 打开「让对方连我」的连接码面板 */
    const val PAIR = "pair"
}
