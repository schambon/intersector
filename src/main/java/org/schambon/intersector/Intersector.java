package org.schambon.intersector;

import static com.mongodb.client.model.Filters.in;

import java.util.ArrayList;
import java.util.List;

import org.bson.BsonInt32;
import org.bson.Document;
import org.bson.conversions.Bson;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;

public class Intersector {

    public static <T> long findAndCount(MongoCollection<T> coll, Bson filter, Bson sort, int limit, List<T> results) {

        var df = filter.toBsonDocument();
        if (df.size() == 0) {
            throw new IllegalArgumentException("Empty filter not supported");
        } else if (df.size() == 1) {

            for (var d : coll.find(filter).sort(sort).limit(limit)) {
                results.add(d);
            }
            return coll.countDocuments(filter);
        }

        var t = new FindAndCountTraversor(limit);
        traverse(coll, filter, sort, t);
        

        for (var d: coll.find(in("_id", t.oids())).sort(sort)) {
            results.add(d);
        }
        return t.count();

    }

    public static <T> List<T> find(MongoCollection<T> coll, Bson filter, Bson sort, int limit) {

        var result = new ArrayList<T>(limit);

        var df = filter.toBsonDocument();
        if (df.size() == 0) {
            throw new IllegalArgumentException("Empty filter not supported");
        } else if (df.size() == 1) {

            for (var d : coll.find(filter).sort(sort).limit(limit)) {
                result.add(d);
            }
            return result;
        }

        var ft = new FindTraversor(limit);
        traverse(coll, filter, sort, ft);
        

        for (var d: coll.find(in("_id", ft.oids())).sort(sort)) {
            result.add(d);
        }
        return result;
    }

    public static long count(MongoCollection<?> coll, Bson filter, Bson sort) {
        var df = filter.toBsonDocument();
        if (df.size() == 0) {
            throw new IllegalArgumentException("Empty filter not supported");
        } else if (df.size() == 1) {
            return coll.countDocuments(filter);
        }

        var ct = new CountTraversor();
        traverse(coll, filter, sort, ct);
        return ct.count();
    }

    static <T> void traverse(MongoCollection<T> coll, Bson filter, Bson sort, Traversor t) {

        var df = filter.toBsonDocument();

        var ds = sort.toBsonDocument().clone();
        if (ds.size() != 1) {
            System.out.println(ds.toJson());
            throw new IllegalArgumentException("Only a single sort spec is supported");
        }
        // we always add a sort by _id to simplify managing the case when distinct records have the same sortkey value
        ds.append("_id", new BsonInt32(1));

        var sortKey = ds.keySet().iterator().next();
        var sortDirection = ds.getInt32(sortKey).getValue();

        var cursors = df.entrySet().parallelStream().map(it -> coll
            .find(new Document(it.getKey(), it.getValue()), Document.class)
            .sort(ds)
            .projection(new Document(it.getKey(), true).append(sortKey, true).append("_id", true))
            .cursor()
        ).toList();

        var head = cursors.get(0);
        var tail = cursors.subList(1, cursors.size());

        Document one;
        if (head.hasNext()) {
            one = head.next();
        } else {
            return;
        }

        var two = next(tail, sortKey, sortDirection);
        if (two == null) {
            return;
        }

        while (t.keepGoing()) {

            var cmp = compareSortKeys(one, two, sortKey, sortDirection);

            if (cmp < 0) {
                // need to advance the head cursor
                if (head.hasNext()) {
                    one = head.next();
                } else {
                    break;
                }
            } else if (cmp > 0) {
                // need to advance the tail cursor
                two = next(tail, sortKey, sortDirection);
                if (two == null) {
                    break;
                }
            } else {
                t.match(one.get("_id"));
                if (head.hasNext()) {
                    one = head.next();
                } else break;
            }
        }

        return;
    }

    private static Document next(List<MongoCursor<Document>> cursors, String sortKey, int sortDirection) {
        if (cursors.size() == 1) {
            var c = cursors.get(0);
            if (c.hasNext()) {
                return c.next();
            } else {
                return null;
            }
        } else {
            var head = cursors.get(0);
            var tail = cursors.subList(1, cursors.size());

            Document one;
            if (head.hasNext()) {
                one = head.next();
            } else {
                return null;
            }
            
            var two = next(tail, sortKey, sortDirection);
            if (two == null) {
                return null;
            }

            while (true) {
                var cmp = compareSortKeys(one, two, sortKey, sortDirection);
                
                if (cmp < 0) {
                    // need to advance the head cursor
                    if (head.hasNext()) {
                        one = head.next();
                    } else {
                        return null;
                    }
                } else if (cmp > 0) {
                    // need to advance the tail cursor
                    two = next(tail, sortKey, sortDirection);
                    if (two == null) {
                        return null;
                    }
                } else { 
                    return one;
                }

            }
        }
        
    }

    static interface Traversor {
        void match(Object oid);
        boolean keepGoing();
    }

    private static class CountTraversor implements Traversor {

        private long count = 0;

        @Override
        public void match(Object oid) {
            count++;
        }

        @Override
        public boolean keepGoing() {
            return true;
        }

        public long count() {
            return count;
        }
    }

    private static class FindTraversor implements Traversor {

        private int limit;
        private List<Object> oids;
        private long count;

        public FindTraversor(int limit) {
            this.limit = limit;
            this.oids = new ArrayList<>(limit);
            this.count = 0l;
        }

        @Override
        public void match(Object oid) {
            count++;
            oids.add(oid);
        }

        @Override
        public boolean keepGoing() {
            return count < limit;
        }

        public List<Object> oids() {
            return oids;
        }

    }

    private static class FindAndCountTraversor implements Traversor {

        private int limit;
        private List<Object> oids;
        private long count;

        public FindAndCountTraversor(int limit) {
            this.limit = limit;
            this.oids = new ArrayList<>(limit);
            this.count = 0l;
        }

        @Override
        public void match(Object oid) {
            count++;
            if (count < limit) oids.add(oid);
        }

        @Override
        public boolean keepGoing() {
            return true;
        }

        public List<Object> oids() {
            return oids;
        }

        public long count() {
            return count;
        }

    }

    private static int compareSortKeys(Document one, Document two, String sortkey, int direction) {
        var s1 = (Comparable) one.get(sortkey);
        var s2 = two.get(sortkey);

        var cmp = s1.compareTo(s2);
        if (cmp < 0) {
            if (direction > 0) {
                return -1;
            } else {
                return 1;
            }
        } else if (cmp > 0) {
            if (direction > 0) {
                return 1;
            } else {
                return -1;
            }
        } else {
            var id1 = (Comparable) one.get("_id");
            var id2 = two.get("_id");

            return id1.compareTo(id2);
        }
    }

    private static boolean gt(int cmp, int direction) {
        if (direction > 0) {
            return cmp > 0;
        } else {
            return cmp < 0;
        }
    }

    private static boolean lt (int cmp, int direction) {
        if (direction > 0) {
            return cmp < 0;
        } else {
            return cmp > 0;
        }
    }


}
