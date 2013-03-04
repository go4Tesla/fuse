/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fusesource.insight.elasticsearch.impl;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.node.Node;
import org.fusesource.insight.elasticsearch.ElasticSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class ElasticSenderImpl implements ElasticSender, Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticSenderImpl.class);

    private final Node node;
    private int max = 1000;
    private Thread thread;
    private volatile boolean running;
    private BlockingQueue<ActionRequest> queue = new LinkedBlockingQueue<ActionRequest>();

    public ElasticSenderImpl(Node node) {
        this.node = node;
    }

    public void init() {
        running = true;
        thread = new Thread(this, "ElasticSender");
        thread.start();
    }

    public void destroy() {
        running = false;
        thread.interrupt();
    }

    public void push(IndexRequest data) {
        queue.add(data);
    }

    public void push(DeleteRequest data) {
        queue.add(data);
    }

    public void run() {
        while (running) {
            try {
                ActionRequest req = queue.take();
                // Send data
                BulkRequest bulk = new BulkRequest();
                int nb = 0;
                while (req != null && (nb == 0 || nb < max)) {
                    bulk.add(req);
                    nb++;
                    req = queue.poll();
                }
                if (bulk.numberOfActions() > 0) {
                    BulkResponse rep = node.client().bulk(bulk).actionGet();
                    for (BulkItemResponse bir : rep.items()) {
                        if (bir.failed()) {
                            LOGGER.warn("Error executing request: {}", bir.getFailureMessage());
                        }
                    }
                }
            } catch (Exception e) {
                if (running) {
                    LOGGER.warn("Error while sending requests", e);
                }
            }
        }
    }

}
