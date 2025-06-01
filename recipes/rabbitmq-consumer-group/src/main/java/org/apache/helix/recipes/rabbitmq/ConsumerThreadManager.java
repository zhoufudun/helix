package org.apache.helix.recipes.rabbitmq;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class ConsumerThreadManager {

    private final ConcurrentHashMap<String, List<String>> consumerMap = new ConcurrentHashMap<>();

    // 私有构造函数（防止外部实例化）
    private ConsumerThreadManager() {}

    // 静态内部类方式实现懒加载、线程安全的单例
    private static class Holder {
        private static final ConsumerThreadManager INSTANCE = new ConsumerThreadManager();
    }

    // 获取单例实例
    public static ConsumerThreadManager getInstance() {
        return Holder.INSTANCE;
    }

    // 获取 consumerMap
    public ConcurrentHashMap<String, List<String>> getConsumerMap() {
        return consumerMap;
    }

    public void addConsumer(String partition, String consumerId) {
        if (!consumerMap.containsKey(consumerId)) {
            consumerMap.put(consumerId, new java.util.ArrayList<>());
        }
        consumerMap.get(consumerId).add(partition);
    }

    public void removeConsumer(String partition, String consumerId) {
        if (consumerMap.containsKey(consumerId)) {
            consumerMap.get(consumerId).remove(partition);
        }
    }
}
