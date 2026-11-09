import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;

/**
 * 把影棚背景的人像做成贴纸（白底）。
 *
 * 思路：背景是接近纯白的平滑渐变，主体肤色/西装色温差别明显，所以不做「全图色差」，
 * 而是从边界出发做受限泛洪 —— 只允许在「很亮且几乎无色彩」的像素里蔓延。
 * 这样既不会因为渐变而抠不干净（相邻像素都够白），也不会顺着肤色渐变一路吃进脸里。
 *
 * 底部边不撒种子：裁剪框底边切的是身体，撒了会把西装当成背景啃掉。
 *
 * 用法：java CutoutMascot.java <src> <dst> <x> <y> <size> <outPx> <minVal> <maxSat>
 */
public class CutoutMascot {
    public static void main(String[] a) throws Exception {
        File src = new File(a[0]);
        File dst = new File(a[1]);
        int x0 = Integer.parseInt(a[2]);
        int y0 = Integer.parseInt(a[3]);
        int size = Integer.parseInt(a[4]);
        int outPx = Integer.parseInt(a[5]);
        float minVal = Float.parseFloat(a[6]);
        float maxSat = Float.parseFloat(a[7]);

        BufferedImage full = ImageIO.read(src);
        int w = size, h = size;
        int[] px = new int[w * h];
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        g.dispose();
        BufferedImage crop = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D cg = crop.createGraphics();
        cg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        cg.drawImage(full, 0, 0, w, h, x0, y0, x0 + size, y0 + size, null);
        cg.dispose();
        crop.getRGB(0, 0, w, h, px, 0, w);

        boolean[] isBg = new boolean[w * h];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int x = 0; x < w; x++) push(q, isBg, px, w, x, 0, minVal, maxSat);
        for (int y = 0; y < h; y++) {
            push(q, isBg, px, w, 0, y, minVal, maxSat);
            push(q, isBg, px, w, w - 1, y, minVal, maxSat);
        }

        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};
        while (!q.isEmpty()) {
            int i = q.poll();
            int x = i % w, y = i / w;
            for (int k = 0; k < 4; k++) {
                int nx = x + dx[k], ny = y + dy[k];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                push(q, isBg, px, w, nx, ny, minVal, maxSat);
            }
        }

        // alpha：背景透明；再收 1px 去掉抠图白边
        int[] out = px.clone();
        int bgCount = 0;
        for (int i = 0; i < out.length; i++) {
            if (isBg[i]) { out[i] = 0; bgCount++; }
        }
        int[] eroded = out.clone();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if ((out[i] >>> 24) == 0) continue;
                boolean edge = (x > 0 && (out[i - 1] >>> 24) == 0)
                        || (x < w - 1 && (out[i + 1] >>> 24) == 0)
                        || (y > 0 && (out[i - w] >>> 24) == 0)
                        || (y < h - 1 && (out[i + w] >>> 24) == 0);
                if (edge) eroded[i] = 0;
            }
        }

        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((eroded[y * w + x] >>> 24) > 8) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        int cw = maxX - minX + 1, ch = maxY - minY + 1;

        // 底部留白单独给：0 表示人物身体被画布切掉，和另外九张贴纸一致
        float bottomPad = a.length > 8 ? Float.parseFloat(a[8]) : 0.04f;
        int pad = Math.round(outPx * 0.04f);
        int padBottom = Math.round(outPx * bottomPad);
        float scale = Math.min((outPx - pad * 2f) / (float) cw,
                (outPx - pad - padBottom) / (float) ch);
        int dw = Math.round(cw * scale), dh = Math.round(ch * scale);
        int dstX = (outPx - dw) / 2;
        int dstY = outPx - padBottom - dh;

        BufferedImage cutout = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        cutout.setRGB(0, 0, w, h, eroded, 0, w);

        BufferedImage res = new BufferedImage(outPx, outPx, BufferedImage.TYPE_INT_ARGB);
        Graphics2D og = res.createGraphics();
        og.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        og.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        og.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        og.setColor(Color.WHITE);
        og.fillRect(0, 0, outPx, outPx);
        og.drawImage(cutout, dstX, dstY, dstX + dw, dstY + dh,
                minX, minY, minX + cw, minY + ch, null);
        og.dispose();

        ImageIO.write(res, "png", dst);
        System.out.printf("crop %d@(%d,%d)  bg=%.1f%%  content=%dx%d  -> %dpx  %dKB%n",
                size, x0, y0, bgCount * 100.0 / (w * h), cw, ch, outPx, dst.length() / 1024);
    }

    private static void push(ArrayDeque<Integer> q, boolean[] isBg, int[] px,
                             int w, int x, int y, float minVal, float maxSat) {
        int i = y * w + x;
        if (isBg[i]) return;
        int c = px[i];
        int r = (c >> 16) & 0xFF, gg = (c >> 8) & 0xFF, b = c & 0xFF;
        int mx = Math.max(r, Math.max(gg, b));
        int mn = Math.min(r, Math.min(gg, b));
        float val = mx / 255f;
        float sat = mx == 0 ? 0f : (mx - mn) / (float) mx;
        if (val >= minVal && sat <= maxSat) {
            isBg[i] = true;
            q.add(i);
        }
    }
}
