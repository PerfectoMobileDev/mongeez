/*
 * Copyright 2011 SecondMarket Labs, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.mongeez.dao;

import com.mongodb.*;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.mongeez.Mongeez;
import org.mongeez.MongoAuth;
import org.mongeez.commands.ChangeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class MongeezDao {
    private final static Logger logger = LoggerFactory.getLogger(MongeezDao.class);

    private MongoDatabase db;
    private List<ChangeSetAttribute> changeSetAttributes;

    public MongeezDao(MongoClient mongoClient, String databaseName) {
        db = mongoClient.getDatabase(databaseName);
        configure();
    }

    public MongeezDao(Mongo mongo, String databaseName) {
        this(mongo, databaseName, null);
    }

    public MongeezDao(Mongo mongo, String databaseName, MongoAuth auth) {
        MongoClient client;
        MongoClientOptions mongoClientOptions = MongoClientOptions.builder().build();

        if (auth == null) {
            client = new MongoClient(mongo.getServerAddressList(), mongoClientOptions);
        } else {
            MongoCredential credentials;
            if (auth.getAuthDb() == null || auth.getAuthDb().equals(databaseName)) {
                credentials = MongoCredential.createCredential(auth.getUsername(), databaseName, auth.getPassword().toCharArray());
            } else {
                credentials = MongoCredential.createCredential(auth.getUsername(), auth.getAuthDb(), auth.getPassword().toCharArray());
            }
            client = new MongoClient(mongo.getServerAddressList(), credentials, mongoClientOptions);
        }

        db = client.getDatabase(databaseName);
        configure();
    }

    private void configure() {
        addTypeToUntypedRecords();
        loadConfigurationRecord();
        dropObsoleteChangeSetExecutionIndices();
        ensureChangeSetExecutionIndex();
    }

    private void addTypeToUntypedRecords() {
        Bson q = Filters.exists("type", false);
        BasicDBObject o = new BasicDBObject("$set", new BasicDBObject("type", RecordType.changeSetExecution.name()));
        getMongeezCollection().updateMany(q, o);
    }

    private void loadConfigurationRecord() {
        Bson q = Filters.eq("type", RecordType.configuration.name());
        Document configRecord = getMongeezCollection().find(q).first();
        if (configRecord == null) {
            if (getMongeezCollection().count() > 0L) {
                // We have pre-existing records, so don't assume that they support the latest features
                configRecord =
                        new Document()
                                .append("type", RecordType.configuration.name())
                                .append("supportResourcePath", false);
            } else {
                configRecord =
                        new Document()
                                .append("type", RecordType.configuration.name())
                                .append("supportResourcePath", true);
            }
            getMongeezCollection().insertOne(configRecord);
        }
        Object supportResourcePath = configRecord.get("supportResourcePath");

        changeSetAttributes = new ArrayList<ChangeSetAttribute>();
        changeSetAttributes.add(ChangeSetAttribute.file);
        changeSetAttributes.add(ChangeSetAttribute.changeId);
        changeSetAttributes.add(ChangeSetAttribute.author);
        if (Boolean.TRUE.equals(supportResourcePath)) {
            changeSetAttributes.add(ChangeSetAttribute.resourcePath);
        }
    }

    /**
     * Removes indices that were generated by versions before 0.9.3, since they're not supported by MongoDB 2.4+
     */
    private void dropObsoleteChangeSetExecutionIndices() {
        String indexName = "type_changeSetExecution_file_1_changeId_1_author_1_resourcePath_1";
        MongoCollection<Document> collection = getMongeezCollection();
        for (Document dbObject : collection.listIndexes()) {
            if (indexName.equals(dbObject.get("name"))) {
                collection.dropIndex(indexName);
            }
        }
    }

    private void ensureChangeSetExecutionIndex() {
        BasicDBObject keys = new BasicDBObject();
        keys.append("type", 1);
        for (ChangeSetAttribute attribute : changeSetAttributes) {
            keys.append(attribute.name(), 1);
        }
        getMongeezCollection().createIndex(keys);
    }

    public boolean wasExecuted(ChangeSet changeSet) {
        BasicDBObject query = new BasicDBObject();
        query.append("type", RecordType.changeSetExecution.name());
        for (ChangeSetAttribute attribute : changeSetAttributes) {
            query.append(attribute.name(), attribute.getAttributeValue(changeSet));
        }
        return getMongeezCollection().count(query) > 0;
    }

    private MongoCollection<Document> getMongeezCollection() {
        return db.getCollection("mongeez");
    }

    public void runScript(String code) {
        final BasicDBObject command = new BasicDBObject();
        command.put("eval", code);
        Document result = db.runCommand(command);
        if (result.containsKey("ok") && result.getDouble("ok") == 0) {
            throw new RuntimeException("Failed executing mongodb script with error: " + result.getString("errmsg"));
        } else if (result.containsKey("retval")) {
            @SuppressWarnings("SpellCheckingInspection") Document retval = (Document) result.get("retval");
            if (retval.containsKey("ok") && retval.getDouble("ok") == 0) {
                throw new RuntimeException("Failed executing mongodb script with error: " + retval.getString("errmsg"));
            }
        }
        logger.info("Script executed successfully with result: " + result);
    }

    public void logChangeSet(ChangeSet changeSet) {
        Document object = new Document();
        object.append("type", RecordType.changeSetExecution.name());
        for (ChangeSetAttribute attribute : changeSetAttributes) {
            object.append(attribute.name(), attribute.getAttributeValue(changeSet));
        }
        object.append("date", DateFormatUtils.ISO_DATETIME_TIME_ZONE_FORMAT.format(System.currentTimeMillis()));
        getMongeezCollection().insertOne(object);
    }
}
