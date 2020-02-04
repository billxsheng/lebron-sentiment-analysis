package spark;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static com.datastax.spark.connector.japi.CassandraJavaUtil.javaFunctions;
import static com.datastax.spark.connector.japi.CassandraJavaUtil.mapToRow;

import com.datastax.spark.connector.SomeColumns;
import constants.Constants;
import models.Tweet;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.*;
import org.apache.spark.streaming.kafka010.ConsumerStrategies;
import org.apache.spark.streaming.kafka010.KafkaUtils;
import org.apache.spark.streaming.kafka010.LocationStrategies;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

public class SparkKafkaConnector {

    public static void connectToTopic() throws InterruptedException {
        SparkConf sparkConf = new SparkConf().setAppName("spark-streaming").setMaster("local[2]").set("spark.executor.memory", "1g");
        sparkConf.set("spark.cassandra.connection.host", "127.0.0.1");
        JavaStreamingContext streamingContext = new JavaStreamingContext(sparkConf, Durations.seconds(1));
        Map<String, Object> kafkaParams = new HashMap<>();
        kafkaParams.put("bootstrap.servers", "localhost:9092");
        kafkaParams.put("key.deserializer", StringDeserializer.class);
        kafkaParams.put("value.deserializer", StringDeserializer.class);
        kafkaParams.put("group.id", "use_a_separate_group_id_for_each_stream");
        kafkaParams.put("auto.offset.reset", "latest");
        kafkaParams.put("enable.auto.commit", false);
        Collection<String> topics = Arrays.asList("oubre");

        /*
            DStream (Discretized Stream) is a continuous sequence of RDDs representing a continuous stream of data. In this case, it is a stream of kafka ConsumerRecord objects
        */
        JavaDStream<ConsumerRecord<String, String>> tweetStream =
                KafkaUtils.createDirectStream(
                        streamingContext,
                        LocationStrategies.PreferConsistent(),
                        ConsumerStrategies.<String, String>Subscribe(topics, kafkaParams));

        /*
            Transforming consumer records into stream of tweets
        */
        JavaDStream<Tweet> tweets = tweetStream.map(record -> {
            JSONObject tweetObj = (JSONObject) new JSONParser().parse(record.value());
            JSONObject userObj = (JSONObject) tweetObj.get("user");
            String username = userObj.get("screen_name").toString();

            String location = null;
            if (userObj.containsKey("location")) {
                location = userObj.get("location").toString();
            }

            String text = tweetObj.get("text").toString();
            Tweet tweet = new Tweet(username, Tweet.processTweet(text), location);

            Tweet.analyze(tweet);
            return tweet;
        });

        /*
            An RDD (or resiliant distributed dataset)
            represents an immutable (cannot be changed), partitioned collection of elements (elements divided into parts)
            that can be operated on in parallel (simultaneous operations, each subproblem runs in a seperate thread and results can be combined *PairRDDFunctions).
        */
        tweets.foreachRDD(rdd -> {
            javaFunctions(rdd).writerBuilder(Constants.CASSANDRA_KEYSPACE_NAME, Constants.CASSANDRA_CORE_TWEETS_TABLE, mapToRow(Tweet.class)).saveToCassandra();
        });

        streamingContext.start();
        streamingContext.awaitTermination();
    }
}