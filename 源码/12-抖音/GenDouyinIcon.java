import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * T70L 桌面图标生成器 —— 抖音
 * 目标：严格符合 launcher_standard/check_icon.py
 *   画布 220x280
 *   实体 alpha>=250 的 bbox 必须正好 (20,20)~(199,259)
 *   含投影 alpha>=8 的 bbox 约 196x256 @ (12,17)
 * 无第三方依赖，仅 JDK。
 */
public class GenDouyinIcon {

    static final int W = 220, H = 280;

    public static void main(String[] args) throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // ---------- 1. 柔光投影（SDF 距离场，精确控制 bbox）----------
        drawShadow(img);

        // ---------- 2. 实体底板：深色圆角矩形 ----------
        RoundRectangle2D body = new RoundRectangle2D.Double(20, 20, 180, 240, 92, 92);
        g.setPaint(new GradientPaint(20, 20, new Color(0x1C1D26), 199, 259, new Color(0x0B0C10)));
        g.fill(body);

        // 顶部细微高光
        g.setPaint(new GradientPaint(20, 20, new Color(255, 255, 255, 26), 20, 120, new Color(255, 255, 255, 0)));
        g.fill(body);

        // ---------- 3. 抖音音符（青/红错位 + 白色主体）----------
        Shape note = notePath();
        // 青色左偏
        g.setColor(new Color(0x25F4EE));
        g.fill(shift(note, -6, 1));
        // 红色右偏
        g.setColor(new Color(0xFE2C55));
        g.fill(shift(note, 6, -1));
        // 白色主体
        g.setColor(Color.WHITE);
        g.fill(note);

        g.dispose();
        File out = new File(args.length > 0 ? args[0] : "ic_douyin.png");
        ImageIO.write(img, "PNG", out);
        System.out.println("生成 " + out.getAbsolutePath());
    }

    /** 抖音音符：符头椭圆 + 竖杆 + 右上符尾 */
    static Shape notePath() {
        Area a = new Area();

        // 符头（略倾斜的椭圆）
        Ellipse2D head = new Ellipse2D.Double(74, 158, 46, 36);
        AffineTransform rot = AffineTransform.getRotateInstance(Math.toRadians(-18), 97, 176);
        a.add(new Area(rot.createTransformedShape(head)));

        // 竖杆
        RoundRectangle2D stem = new RoundRectangle2D.Double(110, 66, 15, 116, 15, 15);
        a.add(new Area(stem));

        // 符尾：从杆顶向右下甩出的弧
        Path2D tail = new Path2D.Double();
        tail.moveTo(112, 68);
        tail.curveTo(126, 62, 142, 68, 146, 84);
        tail.curveTo(150, 100, 141, 112, 130, 116);
        tail.curveTo(146, 108, 152, 94, 145, 80);
        tail.curveTo(139, 68, 126, 62, 112, 68);
        tail.closePath();
        a.add(new Area(tail));

        return a;
    }

    static Shape shift(Shape s, double dx, double dy) {
        AffineTransform t = AffineTransform.getTranslateInstance(dx, dy);
        return t.createTransformedShape(s);
    }

    /**
     * 柔光投影。形状为实体矩形下移 5px，用 SDF 向外衰减 9px。
     * alpha(d) = 200 * (1 - d/9)^2
     * 这样 alpha>=8 的位置约在外扩 7.2px 处，正好落在 (12,17)~(207,272)。
     */
    static void drawShadow(BufferedImage img) {
        double cx = 109.5, cy = 144.5 + 5;
        double hw = 90, hh = 120, r = 46;

        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double px = x + 0.5 - cx;
                double py = y + 0.5 - cy;
                double qx = Math.abs(px) - (hw - r);
                double qy = Math.abs(py) - (hh - r);
                double ax = Math.max(qx, 0), ay = Math.max(qy, 0);
                double d = Math.sqrt(ax * ax + ay * ay) + Math.min(Math.max(qx, qy), 0) - r;

                if (d <= 0) continue;              // 实体内部交给主体绘制
                if (d >= 9) continue;
                double k = 1 - d / 9.0;
                int a = (int) Math.round(200 * k * k);
                if (a < 1) continue;
                if (a > 255) a = 255;

                int old = img.getRGB(x, y);
                int oa = (old >>> 24) & 0xFF;
                // 与已有 alpha 取较大值（只在空白处）
                if (oa < a) img.setRGB(x, y, (a << 24));
            }
        }
    }
}
