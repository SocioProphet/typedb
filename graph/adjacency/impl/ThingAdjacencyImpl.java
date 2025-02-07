/*
 * Copyright (C) 2021 Vaticle
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.vaticle.typedb.core.graph.adjacency.impl;

import com.vaticle.typedb.common.collection.ConcurrentSet;
import com.vaticle.typedb.core.common.collection.ByteArray;
import com.vaticle.typedb.core.common.collection.KeyValue;
import com.vaticle.typedb.core.common.iterator.FunctionalIterator;
import com.vaticle.typedb.core.common.iterator.sorted.SortedIterator.Forwardable;
import com.vaticle.typedb.core.common.iterator.sorted.SortedIterator.Order;
import com.vaticle.typedb.core.graph.adjacency.ThingAdjacency;
import com.vaticle.typedb.core.graph.adjacency.impl.ThingEdgeIterator.InEdgeIteratorImpl;
import com.vaticle.typedb.core.graph.adjacency.impl.ThingEdgeIterator.OutEdgeIteratorImpl;
import com.vaticle.typedb.core.graph.common.Encoding;
import com.vaticle.typedb.core.graph.common.Storage.Key;
import com.vaticle.typedb.core.graph.edge.Edge;
import com.vaticle.typedb.core.graph.edge.ThingEdge;
import com.vaticle.typedb.core.graph.edge.impl.ThingEdgeImpl;
import com.vaticle.typedb.core.graph.iid.EdgeViewIID;
import com.vaticle.typedb.core.graph.iid.IID;
import com.vaticle.typedb.core.graph.iid.InfixIID;
import com.vaticle.typedb.core.graph.iid.SuffixIID;
import com.vaticle.typedb.core.graph.vertex.ThingVertex;
import com.vaticle.typedb.core.graph.vertex.TypeVertex;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Predicate;

import static com.vaticle.typedb.core.common.iterator.Iterators.iterate;
import static com.vaticle.typedb.core.common.iterator.Iterators.link;
import static com.vaticle.typedb.core.common.iterator.sorted.SortedIterator.ASC;
import static com.vaticle.typedb.core.common.iterator.sorted.SortedIterators.Forwardable.emptySorted;
import static com.vaticle.typedb.core.common.iterator.sorted.SortedIterators.Forwardable.iterateSorted;
import static com.vaticle.typedb.core.graph.common.Encoding.Edge.Thing.Optimised.ROLEPLAYER;
import static java.util.Arrays.copyOfRange;

/**
 * This class would benefit from multiple inheritance/traits massively:
 * Dimension 1: In/Out adjacency
 * Dimension 2: Read/WriteBuffered/WritePersisted adjacency
 *
 * The interfaces reflect this well, while the implementation aims to minimise code duplication
 */
public abstract class ThingAdjacencyImpl<EDGE_VIEW extends ThingEdge.View<EDGE_VIEW>> implements ThingAdjacency {

    abstract ThingVertex owner();

    abstract EDGE_VIEW getView(ThingEdge edge);

    InfixIID.Thing infixIID(Encoding.Edge.Thing encoding, IID... lookAhead) {
        return isOut() ?
                InfixIID.Thing.of(encoding.forward(), lookAhead) :
                InfixIID.Thing.of(encoding.backward(), lookAhead);
    }

    EdgeViewIID.Thing viewIID(Encoding.Edge.Thing encoding, ThingVertex adjacent) {
        return EdgeViewIID.Thing.of(owner().iid(), infixIID(encoding), adjacent.iid());
    }

    EdgeViewIID.Thing viewIID(Encoding.Edge.Thing encoding, ThingVertex adjacent, ThingVertex optimised) {
        return EdgeViewIID.Thing.of(
                owner().iid(), infixIID(encoding, optimised.iid().type()),
                adjacent.iid(), SuffixIID.of(optimised.iid().key())
        );
    }

    Key.Prefix<EdgeViewIID.Thing> viewIIDPrefix(Encoding.Edge.Thing encoding, IID... lookahead) {
        return EdgeViewIID.Thing.prefix(owner().iid(), infixIID(encoding, lookahead));
    }

    ThingEdgeImpl.Persisted newPersistedEdge(EdgeViewIID.Thing iid) {
        return new ThingEdgeImpl.Persisted(owner().graph(), iid);
    }

    Forwardable<EDGE_VIEW, Order.Asc> iteratePersistedViews(Encoding.Edge.Thing encoding, IID... lookahead) {
        assert encoding != ROLEPLAYER || lookahead.length >= 1;
        Key.Prefix<EdgeViewIID.Thing> prefix = viewIIDPrefix(encoding, lookahead);
        return owner().graph().storage().iterate(prefix, ASC).mapSorted(
                kv -> getView(newPersistedEdge(EdgeViewIID.Thing.of(kv.key().bytes()))),
                edgeView -> KeyValue.of(edgeView.iid(), ByteArray.empty()),
                ASC
        );
    }

    public static abstract class Read<EDGE_VIEW extends ThingEdge.View<EDGE_VIEW>> extends ThingAdjacencyImpl<EDGE_VIEW> {

        final ThingVertex owner;

        Read(ThingVertex owner) {
            this.owner = owner;
        }

        @Override
        ThingVertex owner() {
            return owner;
        }

        @Override
        public ThingEdge edge(Encoding.Edge.Thing encoding, ThingVertex adjacent) {
            EdgeViewIID.Thing iid = viewIID(encoding, adjacent);
            if (owner.graph().storage().get(iid) == null) return null;
            else return newPersistedEdge(iid);
        }

        @Override
        public ThingEdge edge(Encoding.Edge.Thing encoding, ThingVertex adjacent, ThingVertex optimised) {
            EdgeViewIID.Thing iid = viewIID(encoding, adjacent, optimised);
            if (owner.graph().storage().get(iid) == null) return null;
            else return newPersistedEdge(iid);
        }

        @Override
        public UnsortedEdgeIterator edge(Encoding.Edge.Thing.Optimised encoding) {
            Key.Prefix<EdgeViewIID.Thing> prefix = EdgeViewIID.Thing.prefix(owner().iid(), infixIID(encoding));
            return new UnsortedEdgeIterator(owner.graph().storage().iterate(prefix, ASC)
                    .map(kv -> newPersistedEdge(EdgeViewIID.Thing.of(kv.key().bytes()))));
        }

        public static class In extends Read<ThingEdge.View.Backward> implements ThingAdjacency.In {

            public In(ThingVertex owner) {
                super(owner);
            }

            @Override
            public InEdgeIterator edge(Encoding.Edge.Thing.Base encoding, IID... lookAhead) {
                return new InEdgeIteratorImpl(iteratePersistedViews(encoding, lookAhead), owner, encoding);
            }

            @Override
            public InEdgeIterator edge(Encoding.Edge.Thing.Optimised encoding, TypeVertex roleType, IID... lookAhead) {
                IID[] mergedLookahead = new IID[1 + lookAhead.length];
                mergedLookahead[0] = roleType.iid();
                System.arraycopy(lookAhead, 0, mergedLookahead, 1, lookAhead.length);
                return new InEdgeIteratorImpl(iteratePersistedViews(encoding, mergedLookahead), owner, encoding, roleType);
            }

            @Override
            ThingEdge.View.Backward getView(ThingEdge edge) {
                return edge.backwardView();
            }
        }

        public static class Out extends Read<ThingEdge.View.Forward> implements ThingAdjacency.Out {

            public Out(ThingVertex owner) {
                super(owner);
            }

            @Override
            public OutEdgeIterator edge(Encoding.Edge.Thing.Base encoding, IID... lookAhead) {
                return new OutEdgeIteratorImpl(iteratePersistedViews(encoding, lookAhead), owner, encoding);
            }

            @Override
            public OutEdgeIterator edge(Encoding.Edge.Thing.Optimised encoding, TypeVertex roleType, IID... lookAhead) {
                IID[] mergedLookahead = new IID[1 + lookAhead.length];
                mergedLookahead[0] = roleType.iid();
                System.arraycopy(lookAhead, 0, mergedLookahead, 1, lookAhead.length);
                return new OutEdgeIteratorImpl(iteratePersistedViews(encoding, mergedLookahead), owner, encoding, roleType);
            }

            @Override
            ThingEdge.View.Forward getView(ThingEdge edge) {
                return edge.forwardView();
            }
        }
    }

    public static abstract class Write<EDGE_VIEW extends ThingEdge.View<EDGE_VIEW>>
            extends ThingAdjacencyImpl<EDGE_VIEW> implements ThingAdjacency.Write {

        final ThingVertex.Write owner;
        final ConcurrentMap<InfixIID.Thing, ConcurrentSet<InfixIID.Thing>> infixes;
        // TODO: we can simplify this to ignore the idea of reasoning in write transactions
        final ConcurrentMap<InfixIID.Thing, ConcurrentNavigableMap<EDGE_VIEW, ThingEdgeImpl.Buffered>> edges;

        Write(ThingVertex.Write owner) {
            this.owner = owner;
            this.infixes = new ConcurrentHashMap<>();
            this.edges = new ConcurrentHashMap<>();
        }

        @Override
        ThingVertex owner() {
            return owner;
        }

        IID[] infixTails(ThingEdge edge) {
            if (edge.encoding().isOptimisation()) {
                if (isOut()) {
                    return new IID[]{edge.forwardView().iid().infix().asRolePlayer().tail(), edge.toIID().prefix(), edge.toIID().type()};
                } else {
                    return new IID[]{edge.backwardView().iid().infix().asRolePlayer().tail(), edge.fromIID().prefix(), edge.fromIID().type()};
                }
            } else {
                if (isOut()) return new IID[]{edge.toIID().prefix(), edge.toIID().type()};
                else return new IID[]{edge.fromIID().prefix(), edge.fromIID().type()};
            }
        }

        Forwardable<EDGE_VIEW, Order.Asc> iterateBufferedViews(Encoding.Edge.Thing encoding, IID[] lookahead) {
            ConcurrentNavigableMap<EDGE_VIEW, ThingEdgeImpl.Buffered> result;
            InfixIID.Thing infixIID = infixIID(encoding, lookahead);
            if (lookahead.length == encoding.lookAhead()) {
                return (result = edges.get(infixIID)) != null ? iterateSorted(result.keySet(), ASC) : emptySorted();
            }

            assert lookahead.length < encoding.lookAhead();
            Set<InfixIID.Thing> iids = new HashSet<>();
            iids.add(infixIID);
            for (int i = lookahead.length; i < encoding.lookAhead() && !iids.isEmpty(); i++) {
                Set<InfixIID.Thing> newIIDs = new HashSet<>();
                for (InfixIID.Thing iid : iids) {
                    Set<InfixIID.Thing> someNewIIDs = infixes.get(iid);
                    if (someNewIIDs != null) newIIDs.addAll(someNewIIDs);
                }
                iids = newIIDs;
            }

            return iterate(iids).mergeMap(iid -> {
                ConcurrentNavigableMap<EDGE_VIEW, ThingEdgeImpl.Buffered> res;
                return (res = edges.get(iid)) != null ? iterateSorted(res.keySet(), ASC) : emptySorted();
            }, ASC);
        }

        @Override
        public ThingEdge edge(Encoding.Edge.Thing encoding, ThingVertex adjacent, ThingVertex optimised) {
            assert encoding.isOptimisation();
            Predicate<ThingEdge> predicate = isOut()
                    ? e -> e.to().equals(adjacent) && e.forwardView().iid().suffix().equals(SuffixIID.of(optimised.iid().key()))
                    : e -> e.from().equals(adjacent) && e.backwardView().iid().suffix().equals(SuffixIID.of(optimised.iid().key()));
            Forwardable<EDGE_VIEW, Order.Asc> iterator = iterateBufferedViews(
                    encoding, new IID[]{optimised.iid().type(), adjacent.iid().prefix(), adjacent.iid().type()}
            );
            iterator.forward(isOut() ?
                    getView(new ThingEdgeImpl.Target(encoding, owner, adjacent, optimised.type())) :
                    getView(new ThingEdgeImpl.Target(encoding, adjacent, owner, optimised.type()))
            );
            ThingEdge edge = null;
            while (iterator.hasNext()) {
                if (predicate.test(edge = iterator.next().edge())) break;
                else edge = null;
            }
            iterator.recycle();
            return edge;
        }

        @Override
        public ThingEdge edge(Encoding.Edge.Thing encoding, ThingVertex adjacent) {
            assert !encoding.isOptimisation();
            Predicate<ThingEdge> predicate = isOut() ? e -> e.to().equals(adjacent) : e -> e.from().equals(adjacent);
            Forwardable<EDGE_VIEW, Order.Asc> iterator = iterateBufferedViews(
                    encoding, new IID[]{adjacent.iid().prefix(), adjacent.iid().type()}
            );
            iterator.forward(isOut() ?
                    getView(new ThingEdgeImpl.Target(encoding, owner, adjacent, null)) :
                    getView(new ThingEdgeImpl.Target(encoding, adjacent, owner, null))
            );
            ThingEdge edge = null;
            while (iterator.hasNext()) {
                if (predicate.test(edge = iterator.next().edge())) break;
                else edge = null;
            }
            iterator.recycle();
            return edge;
        }

        private void put(Encoding.Edge.Thing encoding, ThingEdgeImpl.Buffered edge, IID[] infixes,
                         boolean isReflexive) {
            assert encoding.lookAhead() == infixes.length;
            InfixIID.Thing infixIID = infixIID(encoding);
            for (int i = 0; i < encoding.lookAhead(); i++) {
                this.infixes.computeIfAbsent(infixIID, x -> new ConcurrentSet<>()).add(
                        infixIID = infixIID(encoding, copyOfRange(infixes, 0, i + 1))
                );
            }

            this.edges.compute(infixIID, (iid, bufferedEdges) -> {
                EDGE_VIEW edgeView = getView(edge);
                if (bufferedEdges == null) bufferedEdges = new ConcurrentSkipListMap<>();
                bufferedEdges.compute(edgeView, (view, existingEdge) -> {
                    if (existingEdge == null) {
                        if (isOut()) owner.graph().edgeCreated(edge); // only record creation in one direction
                        return edge;
                    } else {
                        assert existingEdge.isInferred() == edge.isInferred();
                        return existingEdge;
                    }
                });
                return bufferedEdges;
            });

            assert !owner.isDeleted();
            owner.setModified();
            if (isReflexive) {
                if (isOut()) ((ThingAdjacencyImpl.Write<?>) edge.to().ins()).putNonReflexive(edge);
                else ((ThingAdjacencyImpl.Write<?>) edge.from().outs()).putNonReflexive(edge);
            }
        }

        private void putNonReflexive(ThingEdgeImpl.Buffered edge) {
            put(edge.encoding(), edge, infixTails(edge), false);
        }

        @Override
        public ThingEdgeImpl put(Encoding.Edge.Thing encoding, ThingVertex.Write adjacent, boolean isInferred) {
            assert !encoding.isOptimisation();
            ThingEdgeImpl.Buffered edge = isOut()
                    ? new ThingEdgeImpl.Buffered(encoding, owner, adjacent, isInferred)
                    : new ThingEdgeImpl.Buffered(encoding, adjacent, owner, isInferred);
            IID[] infixes = new IID[]{adjacent.iid().prefix(), adjacent.iid().type()};
            put(encoding, edge, infixes, true);

            return edge;
        }

        @Override
        public ThingEdge put(Encoding.Edge.Thing encoding, ThingVertex.Write adjacent, ThingVertex.Write optimised,
                             boolean isInferred) {
            assert encoding.isOptimisation();
            ThingEdgeImpl.Buffered edge = isOut()
                    ? new ThingEdgeImpl.Buffered(encoding, owner, adjacent, optimised, isInferred)
                    : new ThingEdgeImpl.Buffered(encoding, adjacent, owner, optimised, isInferred);
            IID[] infixes = new IID[]{optimised.iid().type(), adjacent.iid().prefix(), adjacent.iid().type()};
            put(encoding, edge, infixes, true);
            return edge;
        }

        @Override
        public void remove(ThingEdge edge) {
            InfixIID.Thing infixIID = infixIID(edge.encoding(), infixTails(edge));
            if (edges.containsKey(infixIID)) {
                edges.get(infixIID).remove(getView(edge));
                owner.setModified();
            }
        }

        @Override
        public void deleteAll() {
            iterate(Encoding.Edge.Thing.Base.values()).forEachRemaining(this::delete);
            iterate(Encoding.Edge.Thing.Optimised.values()).forEachRemaining(this::delete);
        }

        @Override
        public void commit() {
            iterate(edges.values()).flatMap(edgeMap -> iterate(edgeMap.values()))
                    .filter(e -> !e.isInferred()).forEachRemaining(Edge::commit);
        }

        public static abstract class Buffered<EDGE_VIEW extends ThingEdge.View<EDGE_VIEW>>
                extends ThingAdjacencyImpl.Write<EDGE_VIEW> {

            Buffered(ThingVertex.Write owner) {
                super(owner);
            }

            @Override
            public UnsortedEdgeIterator edge(Encoding.Edge.Thing.Optimised encoding) {
                return new UnsortedEdgeIterator(iterateBufferedViews(encoding, new IID[]{}).map(ThingEdge.View::edge));
            }

            @Override
            public void delete(Encoding.Edge.Thing encoding) {
                iterateBufferedViews(encoding, new IID[0]).forEachRemaining(comparableEdge -> comparableEdge.edge().delete());
            }

            @Override
            public void delete(Encoding.Edge.Thing encoding, IID... lookAhead) {
                iterateBufferedViews(encoding, lookAhead).forEachRemaining(comparableEdge -> comparableEdge.edge().delete());
            }

            public static class In extends Buffered<ThingEdge.View.Backward> implements ThingAdjacency.Write.In {

                public In(ThingVertex.Write owner) {
                    super(owner);
                }

                @Override
                ThingEdge.View.Backward getView(ThingEdge edge) {
                    return edge.backwardView();
                }

                @Override
                public InEdgeIterator edge(Encoding.Edge.Thing.Base encoding, IID... lookahead) {
                    return new InEdgeIteratorImpl(iterateBufferedViews(encoding, lookahead), owner, encoding);
                }

                @Override
                public InEdgeIterator edge(Encoding.Edge.Thing.Optimised encoding, TypeVertex roleType,
                                           IID... lookahead) {
                    IID[] mergedLookahead = new IID[1 + lookahead.length];
                    mergedLookahead[0] = roleType.iid();
                    System.arraycopy(lookahead, 0, mergedLookahead, 1, lookahead.length);
                    return new InEdgeIteratorImpl(iterateBufferedViews(ROLEPLAYER, mergedLookahead), owner, encoding, roleType);
                }
            }

            public static class Out extends Buffered<ThingEdge.View.Forward> implements ThingAdjacency.Write.Out {

                public Out(ThingVertex.Write owner) {
                    super(owner);
                }

                @Override
                ThingEdge.View.Forward getView(ThingEdge edge) {
                    return edge.forwardView();
                }

                @Override
                public OutEdgeIterator edge(Encoding.Edge.Thing.Base encoding, IID... lookahead) {
                    return new OutEdgeIteratorImpl(iterateBufferedViews(encoding, lookahead), owner, encoding);
                }

                @Override
                public OutEdgeIterator edge(Encoding.Edge.Thing.Optimised encoding, TypeVertex roleType,
                                            IID... lookahead) {
                    IID[] mergedLookahead = new IID[1 + lookahead.length];
                    mergedLookahead[0] = roleType.iid();
                    System.arraycopy(lookahead, 0, mergedLookahead, 1, lookahead.length);
                    return new OutEdgeIteratorImpl(iterateBufferedViews(ROLEPLAYER, mergedLookahead), owner, encoding, roleType);
                }

            }
        }

        public static abstract class Persisted<EDGE_VIEW extends ThingEdge.View<EDGE_VIEW>>
                extends ThingAdjacencyImpl.Write<EDGE_VIEW> {

            Persisted(ThingVertex.Write owner) {
                super(owner);
            }

            @Override
            public UnsortedEdgeIterator edge(Encoding.Edge.Thing.Optimised encoding) {
                return new UnsortedEdgeIterator(iterateEdges(encoding));
            }

            private FunctionalIterator<ThingEdge> iterateEdges(Encoding.Edge.Thing encoding, IID... lookahead) {
                Key.Prefix<EdgeViewIID.Thing> prefix = viewIIDPrefix(encoding, lookahead);
                FunctionalIterator<ThingEdge> storageIterator = owner.graph().storage().iterate(prefix, ASC)
                        .map(keyValue -> newPersistedEdge(EdgeViewIID.Thing.of(keyValue.key().bytes())));
                FunctionalIterator<ThingEdge> bufferedIterator = iterateBufferedViews(encoding, lookahead)
                        .map(ThingEdge.View::edge);
                return link(bufferedIterator, storageIterator).distinct(); // note: has edges can be persisted and buffered
            }

            Forwardable<EDGE_VIEW, Order.Asc> edgeIterator(Encoding.Edge.Thing encoding, IID... lookahead) {
                assert encoding != ROLEPLAYER || lookahead.length >= 1;
                Forwardable<EDGE_VIEW, Order.Asc> storageIter = iteratePersistedViews(encoding, lookahead);
                Forwardable<EDGE_VIEW, Order.Asc> bufferedIter = iterateBufferedViews(encoding, lookahead);
                return bufferedIter.merge(storageIter).distinct(); // note: has edges can be persisted and buffered
            }

            @Override
            public ThingEdge edge(Encoding.Edge.Thing encoding, ThingVertex adjacent) {
                assert !encoding.isOptimisation();
                ThingEdge edge = super.edge(encoding, adjacent);
                if (edge != null) return edge;

                EdgeViewIID.Thing edgeIID = viewIID(encoding, adjacent);
                if (owner.graph().storage().get(edgeIID) == null) return null;
                else return newPersistedEdge(edgeIID);
            }

            @Override
            public ThingEdge edge(Encoding.Edge.Thing encoding, ThingVertex adjacent, ThingVertex optimised) {
                assert encoding.isOptimisation();
                ThingEdge edge = super.edge(encoding, adjacent, optimised);
                if (edge != null) return edge;

                EdgeViewIID.Thing edgeIID = viewIID(encoding, adjacent, optimised);
                if (owner.graph().storage().get(edgeIID) == null) return null;
                else return newPersistedEdge(edgeIID);
            }

            @Override
            public void delete(Encoding.Edge.Thing encoding) {
                iterateEdges(encoding).forEachRemaining(Edge::delete);
            }

            @Override
            public void delete(Encoding.Edge.Thing encoding, IID... lookAhead) {
                iterateEdges(encoding, lookAhead).forEachRemaining(Edge::delete);
            }

            public static class In extends Persisted<ThingEdge.View.Backward> implements ThingAdjacency.Write.In {

                public In(ThingVertex.Write owner) {
                    super(owner);
                }

                @Override
                ThingEdge.View.Backward getView(ThingEdge edge) {
                    return edge.backwardView();
                }

                @Override
                public InEdgeIterator edge(Encoding.Edge.Thing.Base encoding, IID... lookahead) {
                    return new InEdgeIteratorImpl(edgeIterator(encoding, lookahead), owner, encoding);
                }

                @Override
                public InEdgeIterator edge(Encoding.Edge.Thing.Optimised encoding, TypeVertex roleType,
                                           IID... lookahead) {
                    IID[] mergedLookahead = new IID[1 + lookahead.length];
                    mergedLookahead[0] = roleType.iid();
                    System.arraycopy(lookahead, 0, mergedLookahead, 1, lookahead.length);
                    return new InEdgeIteratorImpl(edgeIterator(ROLEPLAYER, mergedLookahead), owner, encoding, roleType);

                }
            }

            public static class Out extends Persisted<ThingEdge.View.Forward> implements ThingAdjacency.Write.Out {

                public Out(ThingVertex.Write owner) {
                    super(owner);
                }

                @Override
                ThingEdge.View.Forward getView(ThingEdge edge) {
                    return edge.forwardView();
                }

                @Override
                public OutEdgeIterator edge(Encoding.Edge.Thing.Base encoding, IID... lookahead) {
                    return new OutEdgeIteratorImpl(edgeIterator(encoding, lookahead), owner, encoding);
                }

                @Override
                public OutEdgeIterator edge(Encoding.Edge.Thing.Optimised encoding, TypeVertex roleType,
                                            IID... lookahead) {
                    IID[] mergedLookahead = new IID[1 + lookahead.length];
                    mergedLookahead[0] = roleType.iid();
                    System.arraycopy(lookahead, 0, mergedLookahead, 1, lookahead.length);
                    return new OutEdgeIteratorImpl(edgeIterator(ROLEPLAYER, mergedLookahead), owner, encoding, roleType);
                }
            }
        }
    }
}
