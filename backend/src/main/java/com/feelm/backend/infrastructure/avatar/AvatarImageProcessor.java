package com.feelm.backend.infrastructure.avatar;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.stereotype.Component;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

@Component
class AvatarImageProcessor {

    private static final int OUTPUT_SIZE = 512;
    private static final long MAX_PIXELS = 25_000_000;
    private static final float JPEG_QUALITY = 0.85f;

    byte[] normalize(byte[] source) {
        try (ImageInputStream input =
                ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (input == null) {
                throw invalid();
            }
            ImageReader reader = reader(input);
            try {
                reader.setInput(input, false, false);
                requireAllowedFormat(reader.getFormatName());
                if (reader.getNumImages(true) != 1) {
                    throw invalid();
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                requireSafeDimensions(width, height);
                IIOMetadata metadata = reader.getImageMetadata(0);
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw invalid();
                }
                return encodeJpeg(square(resizeOriented(decoded, orientation(metadata))));
            } finally {
                reader.dispose();
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid();
        }
    }

    private ImageReader reader(ImageInputStream input) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            throw invalid();
        }
        return readers.next();
    }

    private void requireAllowedFormat(String formatName) {
        String normalized = formatName.toLowerCase(Locale.ROOT);
        if (!normalized.equals("jpeg")
                && !normalized.equals("jpg")
                && !normalized.equals("png")
                && !normalized.equals("webp")) {
            throw invalid();
        }
    }

    private void requireSafeDimensions(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
            throw invalid();
        }
    }

    private BufferedImage resizeOriented(BufferedImage source, String orientation) {
        AffineTransform transform = orientationTransform(source, orientation);
        boolean swapsAxes = orientation.contains("90");
        int width = swapsAxes ? source.getHeight() : source.getWidth();
        int height = swapsAxes ? source.getWidth() : source.getHeight();
        BufferedImage oriented = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = oriented.createGraphics();
        try {
            graphics.drawRenderedImage(source, transform);
        } finally {
            graphics.dispose();
        }
        return oriented;
    }

    private AffineTransform orientationTransform(BufferedImage source, String orientation) {
        int width = source.getWidth();
        int height = source.getHeight();
        return switch (orientation) {
            case "FlipH" -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case "Rotate180" -> new AffineTransform(-1, 0, 0, -1, width, height);
            case "FlipV" -> new AffineTransform(1, 0, 0, -1, 0, height);
            case "FlipHRotate90" -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case "Rotate90" -> new AffineTransform(0, 1, -1, 0, height, 0);
            case "FlipVRotate90" -> new AffineTransform(0, -1, -1, 0, height, width);
            case "Rotate270" -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
    }

    private BufferedImage square(BufferedImage source) {
        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;
        BufferedImage output =
                new BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, OUTPUT_SIZE, OUTPUT_SIZE);
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(
                    source, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE, x, y, x + side, y + side, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), params);
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private String orientation(IIOMetadata metadata) {
        if (metadata == null || !metadata.isStandardMetadataFormatSupported()) {
            return "Normal";
        }
        try {
            return findOrientation(metadata.getAsTree("javax_imageio_1.0"));
        } catch (RuntimeException exception) {
            return "Normal";
        }
    }

    private String findOrientation(Node node) {
        if ("ImageOrientation".equals(node.getNodeName())) {
            NamedNodeMap attributes = node.getAttributes();
            Node value = attributes == null ? null : attributes.getNamedItem("value");
            return value == null ? "Normal" : value.getNodeValue();
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            String found = findOrientation(child);
            if (!"Normal".equals(found)) {
                return found;
            }
        }
        return "Normal";
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.AVATAR_INVALID);
    }
}
