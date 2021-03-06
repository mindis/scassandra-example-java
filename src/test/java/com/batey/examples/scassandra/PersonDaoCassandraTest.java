/*
 * Copyright (C) 2014 Christopher Batey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.batey.examples.scassandra;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.junit.*;
import org.scassandra.cql.PrimitiveType;
import org.scassandra.http.client.*;
import org.scassandra.http.client.PrimingRequest.Result;
import org.scassandra.http.client.types.ColumnMetadata;
import org.scassandra.junit.ScassandraServerRule;

import java.util.*;

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;
import static org.scassandra.cql.ListType.list;
import static org.scassandra.cql.PrimitiveType.*;
import static org.scassandra.http.client.types.ColumnMetadata.column;
import static org.scassandra.matchers.Matchers.*;
import static org.scassandra.http.client.types.ColumnMetadata.*;
import static org.scassandra.cql.PrimitiveType.*;
import static org.scassandra.cql.MapType.*;
import static org.scassandra.cql.SetType.*;
import static org.scassandra.cql.ListType.*;

public class PersonDaoCassandraTest {

    @ClassRule
    public static final ScassandraServerRule SCASSANDRA = new ScassandraServerRule();

    @Rule
    public final ScassandraServerRule resetScassandra = SCASSANDRA;

    public static final int CONFIGURED_RETRIES = 1;

    private static final PrimingClient primingClient = SCASSANDRA.primingClient();
    private static final ActivityClient activityClient = SCASSANDRA.activityClient();

    private PersonDaoCassandra underTest;

    @Before
    public void setup() {
        underTest = new PersonDaoCassandra(8042, CONFIGURED_RETRIES);
        underTest.connect();
        activityClient.clearAllRecordedActivity();
    }

    @After
    public void after() {
        underTest.disconnect();
    }

    @Test
    public void shouldConnectToCassandraWhenConnectCalled() {
        //given
        activityClient.clearConnections();
        //when
        underTest.connect();
        //then
        assertTrue("Expected at least one connection to Cassandra on connect",
                activityClient.retrieveConnections().size() > 0);
    }

    @Test
    public void testRetrievingOfNames() throws Exception {
        // given
        Map<String, ?> row = ImmutableMap.of(
                "first_name", "Chris",
                "last_name", "Batey",
                "age", 29);
        primingClient.prime(PrimingRequest.queryBuilder()
                .withQuery("select * from person")
                .withColumnTypes(column("age", PrimitiveType.INT))
                .withRows(row)
                .build());

        //when
        List<Person> names = underTest.retrievePeople();

        //then
        assertEquals(1, names.size());
        assertEquals("Chris", names.get(0).getName());
    }

    @Test(expected = UnableToRetrievePeopleException.class)
    public void testHandlingOfReadRequestTimeout() throws Exception {
        // given
        PrimingRequest primeReadRequestTimeout = PrimingRequest.queryBuilder()
                .withQuery("select * from person")
                .withResult(Result.read_request_timeout)
                .build();
        primingClient.prime(primeReadRequestTimeout);

        //when
        underTest.retrievePeople();

        //then
    }

    @Test
    public void testCorrectQueryIssuedOnConnect() {
        //given
        Query expectedQuery = Query.builder().withQuery("USE people").withConsistency("ONE").build();

        //when
        underTest.connect();

        //then
        List<Query> queries = activityClient.retrieveQueries();
        assertTrue("Expected query not executed, actual queries:  " + queries, queries.contains(expectedQuery));
    }

    @Test
    public void testCorrectQueryIssuedOnConnectUsingMatcher() {
        //given
        Query expectedQuery = Query.builder().withQuery("USE people").withConsistency("ONE").build();

        //when
        underTest.connect();

        //then
        assertThat(activityClient.retrieveQueries(), containsQuery(expectedQuery));
    }

    @Test
    public void testQueryIssuedWithCorrectConsistency() {
        //given
        Query expectedQuery = Query.builder().withQuery("select * from person").withConsistency("QUORUM").build();

        //when
        underTest.retrievePeople();

         //then
        List<Query> queries = activityClient.retrieveQueries();
        assertTrue("Expected query with consistency QUORUM, found following queries: " + queries,
                queries.contains(expectedQuery));
    }

    @Test
    public void testQueryIssuedWithCorrectConsistencyUsingMatcher() {
        //given
        Query expectedQuery = Query.builder()
                .withQuery("select * from person")
                .withConsistency("QUORUM").build();

        //when
        underTest.retrievePeople();

        //then
        assertThat(activityClient.retrieveQueries(), containsQuery(expectedQuery));
    }

    @Test
    public void testStorePerson() {
        // given
        PrimingRequest preparedStatementPrime = PrimingRequest.preparedStatementBuilder()
                .withQueryPattern(".*person.*")
                .withVariableTypes(VARCHAR, INT, list(TIMESTAMP))
                .build();
        primingClient.prime(preparedStatementPrime);
        underTest.connect();
        Date interestingDate = new Date();
        List<Date> interestingDates = Arrays.asList(interestingDate);

        //when
        underTest.storePerson(new Person("Christopher", 29, interestingDates));

        //then
        PreparedStatementExecution expectedPreparedStatement = PreparedStatementExecution.builder()
                .withPreparedStatementText("insert into person(name, age, interesting_dates) values (?,?,?)")
                .withConsistency("ONE")
                .withVariables("Christopher", 29, Arrays.asList(interestingDate))
                .build();
        assertThat(activityClient.retrievePreparedStatementExecutions(), preparedStatementRecorded(expectedPreparedStatement));
    }

    @Test
    public void testRetrievePeopleViaPreparedStatement() {
        // given
        Date today = new Date();
        Map<String, ?> row = ImmutableMap.of(
                "name", "Chris Batey",
                "age", 29,
                "interesting_dates", Lists.newArrayList(today.getTime())
                );
        PrimingRequest preparedStatementPrime = PrimingRequest.preparedStatementBuilder()
                .withQuery("select * from person where name = ?")
                .withVariableTypes(VARCHAR)
                .withColumnTypes(column("age", INT), column("interesting_dates", list(TIMESTAMP)))
                .withRows(row)
                .build();
        primingClient.prime(preparedStatementPrime);

        //when
        List<Person> names = underTest.retrievePeopleByName("Chris Batey");

        //then
        assertEquals(1, names.size());
        assertEquals("Chris Batey", names.get(0).getName());
        assertEquals(29, names.get(0).getAge());
        assertEquals(Lists.newArrayList(today), names.get(0).getInterestingDates());
    }

    @Test
    public void testRetriesConfiguredNumberOfTimes() throws Exception {
        PrimingRequest readTimeoutPrime = PrimingRequest.queryBuilder()
                .withQuery("select * from person")
                .withResult(Result.read_request_timeout)
                .build();
        primingClient.prime(readTimeoutPrime);

        try {
            underTest.retrievePeople();
        } catch (UnableToRetrievePeopleException e) {
        }

        assertEquals(CONFIGURED_RETRIES + 1, activityClient.retrieveQueries().size());
    }

    @Test(expected = UnableToSavePersonException.class)
    public void testThatSlowQueriesTimeout() throws Exception {
        // given
        PrimingRequest preparedStatementPrime = PrimingRequest.preparedStatementBuilder()
                .withQueryPattern("insert into person.*")
                .withVariableTypes(VARCHAR, INT, list(TIMESTAMP))
                .withFixedDelay(1000)
                .build();
        primingClient.prime(preparedStatementPrime);
        underTest.connect();

        underTest.storePerson(new Person("Christopher", 29, Collections.emptyList()));
    }

    @Test
    public void testLowersConsistency() throws Exception {
        PrimingRequest readtimeoutPrime = PrimingRequest.queryBuilder()
                .withQuery("select * from person")
                .withResult(Result.read_request_timeout)
                .build();
        primingClient.prime(readtimeoutPrime);

        try {
            underTest.retrievePeople();
        } catch (UnableToRetrievePeopleException e) {
        }

        List<Query> queries = activityClient.retrieveQueries();
        assertEquals("Expected 2 attempts. Queries were: " + queries, 2, queries.size());
        assertEquals(Query.builder()
                .withQuery("select * from person")
                .withConsistency("QUORUM").build(), queries.get(0));
        assertEquals(Query.builder()
                .withQuery("select * from person")
                .withConsistency("ONE").build(), queries.get(1));
    }
}
