//package com.sh.engine.service;
//
//import net.sourceforge.tess4j.Tesseract;
//import org.springframework.core.io.ClassPathResource;
//import org.springframework.stereotype.Component;
//
//import javax.annotation.PostConstruct;
//import javax.imageio.ImageIO;
//import java.awt.image.BufferedImage;
//import java.io.File;
//import java.io.InputStream;
//import java.nio.file.Files;
//
///**
// * @Author caiwen
// * @Date 2024 04 21 20 20
// **/
//@Component
//public class OCRServiceImpl implements OCRService {
//    private Tesseract tesseract;
//
//    @PostConstruct
//    private void init() {
//        ClassPathResource classPathResource = new ClassPathResource("tessdata/eng.traineddata");
//        tesseract = new Tesseract();
//        tesseract.setDatapath(classPathResource.getPath());
//        tesseract.setLanguage("eng");
//    }
//
//    @Override
//    public String doOcr(File imageFile) {
//        return null;
//    }
//
//    public static void main(String[] args) throws Exception {
//        ClassPathResource classPathResource = new ClassPathResource("tessdata/eng.traineddata");
//        Tesseract tesseract = new Tesseract();
//        tesseract.setDatapath("E:\\Tesseract-OCR\\tessdata");
////        tesseract.setLanguage("chi_sim");
//
//        File file = new File("F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\snapshot\\seg-1423.jpg");
//        InputStream sbs = Files.newInputStream(file.toPath());
//        BufferedImage bufferedImage = ImageIO.read(sbs);
//
//        // 对图片进行文字识别
//        String s = tesseract.doOCR(bufferedImage);
//        System.out.println(s);
//    }
//}
