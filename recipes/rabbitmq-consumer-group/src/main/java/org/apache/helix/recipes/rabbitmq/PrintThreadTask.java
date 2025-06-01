package org.apache.helix.recipes.rabbitmq;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class PrintThreadTask extends Thread {

    @Override
    public void run() {
        while (true) {
            try {
                Thread.sleep(1000);
                ConcurrentHashMap<String, List<String>> consumerMap = ConsumerThreadManager.getInstance().getConsumerMap();
                for (String partition : consumerMap.keySet()) {
                    List<String> consumerIds = consumerMap.get(partition);
                    System.out.println("Partition: " + partition + " Consumers: " + consumerIds);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

}
