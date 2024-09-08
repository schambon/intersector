package org.schambon.intersector;

import org.bson.Document;
import org.bson.types.ObjectId;

import com.mongodb.client.MongoClients;

import static com.mongodb.client.model.Sorts.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static org.schambon.intersector.Intersector.*;

public class Test {
    

    public static void main(String[] args) {
        var client = MongoClients.create();

        var coll = client.getDatabase("test").getCollection("x");

        var n1 = 89;
        var n2 = 50;
        var n3 = 18;

        var sort = descending("ts");

        var start = System.currentTimeMillis();
        var ct = count(coll, new Document("field1", n1).append("field2", n2), sort);
        System.out.println(format("Two criteria: Counted %d docs in %d ms", ct, System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        ct = coll.countDocuments(new Document("field1", n1).append("field2", n2));
        System.out.println(format("   (Baseline: Counted %d docs in %d ms)", ct, System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        ct = find(coll, new Document("field1", n1).append("field2", n2), sort, 100).size();
        System.out.println(format("Fetched %d docs in %d ms with limit 100", ct, System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        for (var x : coll.find(new Document("field1", n1).append("field2", n2)).sort(sort).limit(100)) {
            // nop
        }
        System.out.println(format("   (Baseline: %d ms)", System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        var threeCrit = new Document("field1", n1).append("field2", n2).append("field3", n3);
        ct = count(coll, threeCrit, sort);
        System.out.println(format("Three criteria: Counted %d docs in %d ms", ct, System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        ct = coll.countDocuments(threeCrit);
        System.out.println(format("   (Baseline: Counted %d docs in %d ms)", ct, System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        var list = find(coll, threeCrit, sort, 10);
        System.out.println(format("Fetched %d docs in %d ms with limit 10", list.size(), System.currentTimeMillis() - start));
        // System.out.println("Found: ");
        // list.forEach(it -> System.out.println(it.getObjectId("_id")));

        // System.out.println("   (Baseline:)");
        start = System.currentTimeMillis();
        for (var x : coll.find(threeCrit).sort(sort).limit(10)) {
            // System.out.println("      " + x.getObjectId("_id"));
        }
        System.out.println(format("   (Baseline: %d ms)", System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        Document search = new Document("field1", new Document("$gte", n1)).append("field2", n2);
        ct = count(coll, search, sort);
        System.out.println(format("Two criteria with one range: Counted %d docs in %d ms", ct, System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        ct = coll.countDocuments(search);
        System.out.println(format("   (Baseline: Counted %d docs in %d ms)", ct, System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        search = new Document("field1", new Document("$gte", n1)).append("field2", new Document("$gte", n2));
        ct = count(coll, search, new Document("ts", -1));
        System.out.println(format("Two criteria with two ranges: Counted %d docs in %d ms", ct, System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        ct = coll.countDocuments(search);
        System.out.println(format("   (Baseline: Counted %d docs in %d ms)", ct, System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        var res = find(coll, search, new Document("ts", -1), 15).stream().map(it -> it.getObjectId("_id")).collect(Collectors.toList());
        System.out.println(format("Fetched 15 in %d ms", System.currentTimeMillis() - start));
        Collections.sort(res);

        start = System.currentTimeMillis();
        var base = new ArrayList<ObjectId>(15);
        for (var oid : coll.find(search).sort(sort).limit(15)) {
            base.add(oid.getObjectId("_id"));
        }
        System.out.println(format("  Baseline fetch: %d ms", System.currentTimeMillis() - start));
        // Collections.sort(base);

        // var setOne = new TreeSet<>(res);
        // var setTwo = new TreeSet<>(base);

        // System.out.println("Found in one, not in two:");
        // for (var d : setOne) {
        //     if (! setTwo.contains(d)) {
        //         System.out.println(d);
        //     }
        // }
        // System.out.println("Found in two, not in one:");
        // for (var d : setTwo) {
        //     if (! setOne.contains(d)) {
        //         System.out.println(d);
        //     }
        // }
    }

}
