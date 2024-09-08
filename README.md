MongoDB Index Intersector
=========================

MongoDB supports B-Tree indexes, which are great for optimising known query patterns. However, the situation is different when you don't know your query patterns ahead of time -- typically, when you have a generic list UI where users can add any filter they like.

There is no true way to handle this with regular indexes. The best one can do is to create a set of indexes for the most common queries, or force a selection of a few fields that reduce the scope of the search. This still falls short of a snappy experience as soon as you have a few million records to sift through.

Most of the time, it's not a problem! MongoDB Atlas also has Atlas Search indexes, which are Lucene-backed inverted indexes that are specifically suited for this type of use case. But some companies have specific policies that prevent them from using cloud services such as Atlas, so only the core database of MongoDB is available.

This project is for them: how to optimize filtering through millions of records, with ad-hoc queries, and more or less acceptable performance?

One way of achieving this is to use index intersection, i.e. create an index for each searchable attribute, and somehow cross these indexes. Unfortunately, the core server doesn't use index intersection, because it's only efficient in a few very specific conditions. But we can put ourselves in these conditions and intersect indexes manually. This is what this project does.

How does it work?
-----------------

Suppose you have a simple query on two fields: `collection.find({ field1: 10, field2: 89 }).sort({ timestamp: 1 })` (but you don't know which two fields ahead of time). You do know that all your queries will always be sorted by `timestamp`, though. This is crucial.

First, create indexes on each of your searchable fields, of this shape: `{ fieldname: 1, timestamp: 1, _id: 1}`, ie field, sort criteria and _id (always ascending).

Then, run the following query but do not consume the returned cursor: `collection.find({ field1: 10 }, {timestamp:1, _id:1}).sort({timestamp:1, _id:1})`. Set aside the cursor in a variable. Do the same for `field2`: `collection.find({ field2: 89 }, {timestamp:1, _id:1}).sort({timestamp:1, _id:1})`.

We now have two cursors, let's call them `c1` and `c2`, filtering by `field1`/`field2`, sorted by `timestamp` and `_id`, and projecting `timestamp` and `_id`. It should be obvious that every `_id` that is present in both result sets matches both conditions. Since both cursors are sorted along the same axis (timestamp/_id) we can simply walk the cursors in tandem. In pseudo-code, this looks like this:

```
one = c1.next()
two = c2.next()

while cursors_not_exhausted():
   ts_id_1 = extract_sortkey(one)
   ts_id_2 = extract_sortkey(two)

   if ts_id_1 < ts_id_2:
      # need to advance cursor 1
      one = c1.next()
   else if ts_id_1 > ts_id_2:
      # need to advance cursor 2
      two = c2.next()
   else:
      # sort keys are equal, ie _ids are equal
      add_match(extract_id(one))
```

The implementation gets a little more complex to take care of descending sorts and generalizing to _n_ criteria (instead of two), but the idea is there.

Is it really faster?
--------------------

I invite you to try out for yourself!

In the `resources` folder there is a [SimRunner](https://github.com/schambon/SimRunner) template that generates ten million records with a combination of numeric fields, plus the indexes we need.

The class `org.schambon.intersector.Test` contains a few tests running on this collection:
- two equality criteria (count and fetch the first 100 docs)
- three equality criteria
- one range and one equality
- two ranges

Most times, we compare with a "baseline" which is plainly executing the full query on the same set of indexes. It's moderately realistic - if one really can't identify common queries, the best one can do is to create indexes on all fields and hope that one of the indexes will narrow down the filtering enough. In this particular scenario, this doesn't hold - by construction, there are around 100,000 records for each value of each field: the _baseline_ query will always have to `FETCH` and filter through 100,000 records in the collection.

On my laptop (M1 Pro Macbook with 16GB RAM, a couple of years from cutting edge), running a local MongoDB server of the latest 8.0 release candidate (`8.0.0-rc20` to be precise), I get the following results:

- two equalities, count (1,039 results): 339 ms vs baseline of 11,429 ms (~97% faster)
- two equalities, fetch first 100: 244 ms vs 2,131 ms (~89% faster)
- three equalities, count (6 results): 312 ms vs 40,509 ms (~99% faster)
- three equalities, fetch all results: 284 ms vs 8,958 ms (~97% faster)
- one range, one equality, count (11,487 results): 4,476 ms vs 9,208 ms (~51% faster)
- two ranges, count (581,293 results): 21,805 ms vs 128,793 ms (~83% faster)
- two ranges, fetch first 15: 12,740 ms vs 118,593 ms (~89% faster)

Of course, this specific test is highly unrealistic - the data distribution is highly homogeneous, and so on. In a real-world situation, it's possible, or even likely, that the observed performance would be very different. So we shouldn't read too much in the performance numbers. But we can still make some observations:

- _in the right conditions_, index intersection can be significantly faster than naive single-indexing (it's never going to be as fast as properly optimized compound indexes tailored for a given query, though)
- Range queries are much harder than equality. No surprise: our cursors need to perform a server-side in-memory sort on large result sets.
- Larger result sets take longer to count/fetch than small ones. Well, duh.

A few end notes on performance:
- All this isn't free - we open a lot more cursors on the server. It's okay for a handful of users using a GUI, it may not be the case if you have tens of thousands of clients running queries in a loop.
- There is a lot more traffic between the server and the application, too. In a real deployment with the server on a separate host, I expect the performance to be highly dependant on network speed.
- The application also does a lot more work, that needs to be accounted for.
- In real-world scenarios, you will often have a "prefix" search (a mandatory field or three that significantly partition the dataset). This demo project doesn't implement anything of the sort; you'll have to add your prefixes (equality only!) as a prefix to all field indexes and also modify the code so all cursors include the prefix filtering.

Last but not least: I will leave testing with Atlas Search to the reader, but I expect it to blow my little prototype in the water :)
