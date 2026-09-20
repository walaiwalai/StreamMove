package com.sh.engine.processor.plugin.highlight;

import com.alibaba.fastjson.JSONObject;
import com.sh.engine.model.highlight.FactPreservingCoverStyle;
import org.junit.Assert;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public class FactPreservingCoverStylerTest {
    @Test
    public void shouldNotCopyQwenSpatialObjectsIntoSourceFrame() throws Exception {
        BufferedImage source = solidImage(new Color(40, 90, 130));
        BufferedImage qwen = solidImage(new Color(55, 105, 145));
        Graphics2D graphics = qwen.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(160, 0, 160, 180);
        graphics.dispose();

        FactPreservingCoverStyle style = new FactPreservingCoverStyler().transfer(
                encode(source), encode(qwen));

        BufferedImage actual = ImageIO.read(
                new ByteArrayInputStream(style.getImageData()));
        Assert.assertEquals(actual.getRGB(80, 90), actual.getRGB(240, 90));
        JSONObject proof = style.toAuditMetadata();
        Assert.assertEquals(Boolean.TRUE, proof.getBoolean("sourceCoordinateRetention"));
        Assert.assertEquals(Boolean.FALSE, proof.getBoolean("qwenSpatialPixelsUsed"));
        Assert.assertEquals(Boolean.TRUE, proof.getBoolean("monotonicPerChannel"));
    }

    @Test
    public void shouldKeepEveryChannelGainInsideSafetyBounds() throws Exception {
        BufferedImage source = solidImage(new Color(64, 96, 128));
        BufferedImage qwen = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = qwen.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, 160, 180);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(160, 0, 160, 180);
        graphics.dispose();

        FactPreservingCoverStyle style = new FactPreservingCoverStyler().transfer(
                encode(source), encode(qwen));

        for (double gain : style.getChannelGains()) {
            Assert.assertTrue(gain >= FactPreservingCoverStyler.MINIMUM_STYLE_GAIN);
            Assert.assertTrue(gain <= FactPreservingCoverStyler.MAXIMUM_STYLE_GAIN);
        }
    }

    private BufferedImage solidImage(Color color) {
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        return image;
    }

    private byte[] encode(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }
}
