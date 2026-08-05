package com.nexusagent.common.id;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

public class SnowflakeIdGeneratorTest {

    @Test
    void shouldGeneratePositiveAndIncreasingIds(){
        IdGenerator generator = new SnowflakeIdGenerator(0);

        long previous = generator.nextId();

        assertTrue(previous > 0);

        for(int i = 0; i < 10_000; i++){
            long current = generator.nextId();

            assertTrue(current > previous);
            previous = current;
        }
    }

    @Test
    void shouldRejectInvalidId(){
        assertThrows(
                IllegalArgumentException.class,
                () -> new SnowflakeIdGenerator(-1)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new SnowflakeIdGenerator(1024)
        );
    }

    @Test
    void shouldGenerateUniqueIdsConcurrently() throws Exception {
        int taskCount = 8_000;
        IdGenerator generator = new SnowflakeIdGenerator(1);
        Set<Long> generatedIds = ConcurrentHashMap.newKeySet();

        try(var executor = Executors.newVirtualThreadPerTaskExecutor()){
            List<Future<Long>> futures = IntStream.range(0, taskCount)
                    .mapToObj(ignored -> executor.submit(generator::nextId))
                    .toList();

            for(Future<Long> future : futures){
                generatedIds.add(future.get());
            }
        }

        assertEquals(taskCount, generatedIds.size());
    }

}
