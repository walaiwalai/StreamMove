package com.sh.engine.processor.plugin.highlight.valorant;

import com.sh.engine.model.highlight.core.InMemoryVideoFrame;
import com.sh.engine.processor.plugin.highlight.HighlightOcrClient;
import org.junit.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ValorantFrameBatchTest {
    @Test public void twoConsumersProcessEveryFrameAndEachReceivesItsOwnEndMarker() throws Exception {
        ValorantHighlightTimelineProvider provider=new ValorantHighlightTimelineProvider();
        HighlightOcrClient ocr=mock(HighlightOcrClient.class);
        ValorantKillFeedClassifier classifier=mock(ValorantKillFeedClassifier.class);
        set(provider,"ocrClient",ocr);set(provider,"classifier",classifier);
        List<List<String>> batches=Collections.synchronizedList(new ArrayList<>());
        when(ocr.recognizeBatch(anyList(),anyList())).thenAnswer(call -> {
            List<byte[]> images=call.getArgument(0);List<String> names=call.getArgument(1);
            assertEquals(images.size(),names.size());assertTrue(images.size()<=8);
            batches.add(new ArrayList<>(names));return Collections.nCopies(images.size(),Collections.emptyList());
        });
        when(classifier.classifyRows(anyList(),anyInt(),anyInt(),anyFloat())).thenReturn(Collections.emptyList());
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(40,20,BufferedImage.TYPE_INT_RGB),"jpg",bytes);
        BlockingQueue<InMemoryVideoFrame> queue=new ArrayBlockingQueue<>(32);
        for(int i=0;i<19;i++)queue.add(new InMemoryVideoFrame(i*4,bytes.toByteArray()));
        Field end=ValorantHighlightTimelineProvider.class.getDeclaredField("END_OF_STREAM");end.setAccessible(true);
        InMemoryVideoFrame marker=(InMemoryVideoFrame)end.get(null);queue.add(marker);queue.add(marker);
        Method consume=ValorantHighlightTimelineProvider.class.getDeclaredMethod("consumeFrames",File.class,BlockingQueue.class,Queue.class);consume.setAccessible(true);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Void> worker=() -> {consume.invoke(provider,new File("P01.mp4"),queue,new ConcurrentLinkedQueue<>());return null;};
            Future<Void> a=pool.submit(worker),b=pool.submit(worker);a.get(3,TimeUnit.SECONDS);b.get(3,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertTrue(queue.isEmpty());Set<String> names=new HashSet<>();int count=0;
        for(List<String> batch:batches){count+=batch.size();for(String name:batch)assertTrue(names.add(name));}
        assertEquals(19,count);assertEquals(19,names.size());assertTrue(batches.size()<19);
        verify(ocr,never()).recognize(any(byte[].class),anyString());
    }
    private void set(Object bean,String name,Object value)throws Exception{Field f=bean.getClass().getDeclaredField(name);f.setAccessible(true);f.set(bean,value);}
}
