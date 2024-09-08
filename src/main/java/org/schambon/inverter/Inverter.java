package org.schambon.inverter;

import static com.mongodb.client.model.Filters.in;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.bson.BsonInt32;
import org.bson.Document;
import org.bson.conversions.Bson;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;

public class Inverter {

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
        execute(coll, filter, sort, ft);
        

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
        execute(coll, filter, sort, ct);
        return ct.count();
    }

    static <T> void execute(MongoCollection<T> coll, Bson filter, Bson sort, Traversor t) {

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
            var c1 = (Comparable) one.get(sortKey);
            var c2 = two.get(sortKey);

            var cmp = c1.compareTo(c2);

            if (lt(cmp, sortDirection)) {
                // need to advance the head cursor
                if (head.hasNext()) {
                    one = head.next();
                } else {
                    break;
                }
            } else if (gt(cmp, sortDirection)) {
                // need to advance the tail cursor
                two = next(tail, sortKey, sortDirection);
                if (two == null) {
                    break;
                }
            } else {

                var id1 = one.get("_id");
                var id2 = two.get("_id");
                if (id1.equals(id2)) {
                    t.match(id1);
                    if (head.hasNext()) {
                        one = head.next();
                    } else break;
                } else {
                    var point = one.get(sortKey);
                    while(true) {

                        var cmp_id = ((Comparable)id1).compareTo(id2);
                        if (cmp_id < 0) {
                            if (head.hasNext()) {
                                one = head.next();
                                if (! one.get(sortKey).equals(point)) {
                                    break;
                                }
                                id1 = one.get("_id");
                            } else {
                                break;
                            }
                        } else if (cmp_id > 0) {
                            two = next(tail, sortKey, sortDirection);
                            if (two == null) {
                                break;
                            }
                            if (! two.get(sortKey).equals(point)) {
                                break;
                            }
                            id2 = two.get("_id");
                        } else {
                            t.match(id1);
                            if (head.hasNext()) {
                                one = head.next();
                                id1 = one.get("_id");
                                if (!one.get(sortKey).equals(point)) break;
                            } else break;
                        }
                    }

                    if (! head.hasNext() || two == null) break;
                }
            }
        }

        return;
    }

    static interface Traversor {
        void match(Object oid);
        boolean keepGoing();
    }

    static class CountTraversor implements Traversor {

        private int count = 0;

        @Override
        public void match(Object oid) {
            count++;
        }

        @Override
        public boolean keepGoing() {
            return true;
        }

        public int count() {
            return count;
        }
    }

    static class FindTraversor implements Traversor {

        private int limit;
        private List<Object> oids;
        private int count;

        public FindTraversor(int limit) {
            this.limit = limit;
            this.oids = new ArrayList<>(limit);
            this.count = 0;
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

    static Document next(List<MongoCursor<Document>> cursors, String sortKey, int sortDirection) {
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
                var c1 = (Comparable) one.get(sortKey);
                var c2 = (Comparable) two.get(sortKey);

                var cmp = c1.compareTo(c2);

                
                if (lt(cmp, sortDirection)) {
                    // need to advance the head cursor
                    if (head.hasNext()) {
                        one = head.next();
                    } else {
                        return null;
                    }
                } else if (gt(cmp, sortDirection)) {
                    // need to advance the tail cursor
                    two = next(tail, sortKey, sortDirection);
                    if (two == null) {
                        return null;
                    }
                } else { // seems like we have a match!
                    var id1 = one.get("_id");
                    var id2 = two.get("_id");
                    if (id1.equals(id2)) {
                        return one;
                    } else {
                        var point = one.get(sortKey);
                        while (true) {
                            var cmp_id = ((Comparable)id1).compareTo(id2);
                            if (cmp_id < 0) {
                                if (head.hasNext()) {
                                    one = head.next();
                                    if (! one.get(sortKey).equals(point)) {
                                        break;
                                    }
                                    id1 = one.get("_id");
                                } else break;
                            } else if (cmp_id > 0) {
                                two = next(tail, sortKey, sortDirection);
                                if (two == null) break;
                                if (! two.get(sortKey).equals(point)) break;
                                id2 = two.get("_id");
                            } else {
                                return one;
                            }
                        }
                    }
                }

            }
        }
        
    }


    static boolean gt(int cmp, int direction) {
        if (direction > 0) {
            return cmp > 0;
        } else {
            return cmp < 0;
        }
    }

    static boolean lt (int cmp, int direction) {
        if (direction > 0) {
            return cmp < 0;
        } else {
            return cmp > 0;
        }
    }
}
