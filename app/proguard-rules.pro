# 自定义 View 与小组件相关类保留
#
# 这几个类在 XML 里以全限定名出现，由 LayoutInflater 反射创建。
# R8 不认识这种引用，不 keep 会在 release 包里 inflate 失败——
# debug 包看不出来，一上 release 就白屏，是最典型的坑。
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep class com.example.myzuoye.ui.** { *; }
-keep class com.example.myzuoye.widget.** { *; }

# 小组件的 RemoteViews 通过反射设置图片，保留这几个方法
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 协程的调试信息可以去掉，但保留必要注解
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn kotlinx.coroutines.**
