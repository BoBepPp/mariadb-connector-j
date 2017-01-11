/*
MariaDB Client for Java

Copyright (c) 2012-2014 Monty Program Ab.
Copyright (c) 2015-2016 MariaDB Ab.

This library is free software; you can redistribute it and/or modify it under
the terms of the GNU Lesser General Public License as published by the Free
Software Foundation; either version 2.1 of the License, or (at your option)
any later version.

This library is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
for more details.

You should have received a copy of the GNU Lesser General Public License along
with this library; if not, write to Monty Program Ab info@montyprogram.com.

This particular MariaDB Client for Java file is work
derived from a Drizzle-JDBC. Drizzle-JDBC file which is covered by subject to
the following copyright and notice provisions:

Copyright (c) 2009-2011, Marcus Eriksson

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:
Redistributions of source code must retain the above copyright notice, this list
of conditions and the following disclaimer.

Redistributions in binary form must reproduce the above copyright notice, this
list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.

Neither the name of the driver nor the names of its contributors may not be
used to endorse or promote products derived from this software without specific
prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS  AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
OF SUCH DAMAGE.
*/

package org.mariadb.jdbc.internal.protocol;

import org.mariadb.jdbc.LocalInfileInterceptor;
import org.mariadb.jdbc.MariaDbConnection;
import org.mariadb.jdbc.MariaDbStatement;
import org.mariadb.jdbc.UrlParser;
import org.mariadb.jdbc.internal.MariaDbServerCapabilities;
import org.mariadb.jdbc.internal.MariaDbType;
import org.mariadb.jdbc.internal.packet.*;
import org.mariadb.jdbc.internal.packet.dao.ColumnInformation;
import org.mariadb.jdbc.internal.packet.dao.parameters.ParameterHolder;
import org.mariadb.jdbc.internal.packet.result.ErrorPacket;
import org.mariadb.jdbc.internal.packet.send.SendChangeDbPacket;
import org.mariadb.jdbc.internal.packet.send.SendPingPacket;
import org.mariadb.jdbc.internal.queryresults.*;
import org.mariadb.jdbc.internal.queryresults.resultset.MariaSelectResultSet;
import org.mariadb.jdbc.internal.stream.MaxAllowedPacketException;
import org.mariadb.jdbc.internal.stream.PacketOutputStream;
import org.mariadb.jdbc.internal.util.BulkStatus;
import org.mariadb.jdbc.internal.util.ExceptionMapper;
import org.mariadb.jdbc.internal.util.Utils;
import org.mariadb.jdbc.internal.util.buffer.Buffer;
import org.mariadb.jdbc.internal.util.constant.ServerStatus;
import org.mariadb.jdbc.internal.util.constant.SessionChangeType;
import org.mariadb.jdbc.internal.util.dao.ClientPrepareResult;
import org.mariadb.jdbc.internal.util.dao.PrepareResult;
import org.mariadb.jdbc.internal.util.dao.ServerPrepareResult;

import java.io.*;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.locks.ReentrantLock;

import static org.mariadb.jdbc.internal.util.SqlStates.*;


public class AbstractQueryProtocol extends AbstractConnectProtocol implements Protocol {

    private int transactionIsolationLevel = 0;

    private InputStream localInfileInputStream;

    private int maxRows;  /* max rows returned by a statement */

    private volatile int statementIdToRelease = -1;


    /**
     * Get a protocol instance.
     *
     * @param urlParser connection URL infos
     * @param lock      the lock for thread synchronisation
     */

    public AbstractQueryProtocol(final UrlParser urlParser, final ReentrantLock lock) {
        super(urlParser, lock);
    }

    public void executeQuery(final String sql) throws SQLException {
        executeQuery(isMasterConnection(), new Results(), sql);
    }

    /**
     * Execute query directly to outputStream.
     *
     * @param mustExecuteOnMaster was intended to be launched on master connection
     * @param results             result
     * @param sql                 the query to executeInternal
     * @throws SQLException exception
     */
    @Override
    public void executeQuery(boolean mustExecuteOnMaster, Results results, final String sql) throws SQLException {

        cmdPrologue();

        try {

            writer.send(sql, Packet.COM_QUERY);
            getResult(results);

        } catch (SQLException sqlException) {
            throw addQueryInfo(sql, sqlException);
        } catch (MaxAllowedPacketException e) {
            if (e.isMustReconnect()) connect();
            throw new SQLNonTransientConnectionException("Could not send query: " + e.getMessage(), UNDEFINED_SQLSTATE.getSqlState(), e);
        } catch (IOException e) {
            throw new SQLNonTransientConnectionException("Could not send query: " + e.getMessage(), CONNECTION_EXCEPTION.getSqlState(), e);
        }

    }

    /**
     * Execute a unique clientPrepareQuery.
     *
     * @param mustExecuteOnMaster was intended to be launched on master connection
     * @param results             results
     * @param clientPrepareResult clientPrepareResult
     * @param parameters          parameters
     * @throws SQLException exception
     */
    public void executeQuery(boolean mustExecuteOnMaster, Results results, final ClientPrepareResult clientPrepareResult,
                             ParameterHolder[] parameters) throws SQLException {
        cmdPrologue();
        try {

            if (clientPrepareResult.getParamCount() == 0 && !clientPrepareResult.isQueryMultiValuesRewritable()) {

                ComExecute.sendDirect(writer, clientPrepareResult.getQueryParts().get(0));

            } else {

                writer.startPacket(0);
                ComExecute.sendSubCmd(writer, clientPrepareResult, parameters);
                writer.finishPacketWithoutRelease(true);

            }

            getResult(results);

        } catch (SQLException queryException) {
            throw throwErrorWithQuery(parameters, queryException, clientPrepareResult);
        } catch (MaxAllowedPacketException e) {
            if (e.isMustReconnect()) connect();
            throw new SQLNonTransientConnectionException("Could not execute query", UNDEFINED_SQLSTATE.getSqlState(), e);
        } catch (IOException e) {
            throw new SQLNonTransientConnectionException("Could not execute query", CONNECTION_EXCEPTION.getSqlState(), e);
        } finally {
            writer.releaseBufferIfNotLogging();
        }
    }

    /**
     * Execute clientPrepareQuery batch.
     *
     * @param mustExecuteOnMaster was intended to be launched on master connection
     * @param results             results
     * @param clientPrepareResult ClientPrepareResult
     * @param parametersList      List of parameters
     * @throws SQLException exception
     */
    public void executeBatchMulti(boolean mustExecuteOnMaster, Results results, final ClientPrepareResult clientPrepareResult,
                                  final List<ParameterHolder[]> parametersList) throws SQLException {
        cmdPrologue();

        new AbstractMultiSend(this, writer, results, clientPrepareResult, parametersList) {

            @Override
            public void sendCmd(PacketOutputStream writer, Results results,
                                List<ParameterHolder[]> parametersList, List<String> queries, int paramCount, BulkStatus status,
                                PrepareResult prepareResult)
                    throws SQLException, IOException {

                ParameterHolder[] parameters = parametersList.get(status.sendCmdCounter);
                writer.startPacket(0);
                ComExecute.sendSubCmd(writer, clientPrepareResult, parameters);
                writer.finishPacketWithoutRelease(true);

            }


            @Override
            public SQLException handleResultException(SQLException qex, Results results,
                                                        List<ParameterHolder[]> parametersList, List<String> queries, int currentCounter,
                                                        int sendCmdCounter, int paramCount, PrepareResult prepareResult)
                    throws SQLException {

                int counter = results.getCurrentStatNumber() - 1;
                ParameterHolder[] parameters = parametersList.get(counter);
                List<byte[]> queryParts = clientPrepareResult.getQueryParts();
                String sql = new String(queryParts.get(0));

                for (int i = 0; i < paramCount; i++) {
                    sql += parameters[i].toString() + new String(queryParts.get(i + 1));
                }

                return addQueryInfo(sql, qex);
            }


            @Override
            public int getParamCount() {
                return clientPrepareResult.getQueryParts().size() - 1;
            }


            @Override
            public int getTotalExecutionNumber() {
                return parametersList.size();
            }

        }.executeBatch();

    }

    /**
     * Execute list of queries not rewritable.
     *
     * @param mustExecuteOnMaster was intended to be launched on master connection
     * @param results             result object
     * @param queries             list of queries
     * @throws SQLException exception
     */
    public void executeBatch(boolean mustExecuteOnMaster, Results results, final List<String> queries)
            throws SQLException {
        cmdPrologue();

        new AbstractMultiSend(this, writer, results, queries) {

            @Override
            public void sendCmd(PacketOutputStream writer, Results results,
                                List<ParameterHolder[]> parametersList, List<String> queries, int paramCount, BulkStatus status,
                                PrepareResult prepareResult)
                    throws SQLException, IOException {

                String sql = queries.get(status.sendCmdCounter);
                writer.send(sql, Packet.COM_QUERY);

            }

            @Override
            public SQLException handleResultException(SQLException qex, Results results,
                                                        List<ParameterHolder[]> parametersList, List<String> queries, int currentCounter,
                                                        int sendCmdCounter, int paramCount, PrepareResult prepareResult)
                    throws SQLException {

                String sql = queries.get(currentCounter + sendCmdCounter);
                return addQueryInfo(sql, qex);

            }

            @Override
            public int getParamCount() {
                return -1;
            }

            @Override
            public int getTotalExecutionNumber() {
                return queries.size();
            }

        }.executeBatch();

    }

    /**
     * Prepare query on server side.
     * Will permit to know the parameter number of the query, and permit to send only the data on next results.
     * <p>
     * For failover, two additional information are in the resultset object :
     * - current connection : Since server maintain a state of this prepare statement, all query will be executed on this particular connection.
     * - executeOnMaster : state of current connection when creating this prepareStatement (if was on master, will only be executed on master.
     * If was on a slave, can be execute temporary on master, but we keep this flag,
     * so when a slave is connected back to relaunch this query on slave)
     *
     * @param sql             the query
     * @param executeOnMaster state of current connection when creating this prepareStatement
     * @return a ServerPrepareResult object that contain prepare result information.
     * @throws SQLException if any error occur on connection.
     */
    @Override
    public ServerPrepareResult prepare(String sql, boolean executeOnMaster) throws SQLException {

        cmdPrologue();

        lock.lock();
        try {

            if (options.cachePrepStmts) {

                String key = new StringBuilder(database).append("-").append(sql).toString();
                ServerPrepareResult pr = serverPrepareStatementCache.get(key);

                if (pr != null && pr.incrementShareCounter()) {
                    return pr;
                }

            }

            writer.startPacket(0, true);
            ComStmtPrepare comStmtPrepare = new ComStmtPrepare(this, sql);
            comStmtPrepare.send(writer);
            ServerPrepareResult result = comStmtPrepare.read(packetFetcher, isEofDeprecated());

            return result;

        } catch (SQLException queryException) {
            throw addQueryInfo(sql, queryException);
        } catch (MaxAllowedPacketException e) {
            if (e.isMustReconnect()) connect();
            throw new SQLNonTransientConnectionException("Could not execute query \"" + sql + "\"", UNDEFINED_SQLSTATE.getSqlState(), e);
        } catch (IOException e) {
            throw new SQLNonTransientConnectionException("Could not prepare query \"" + sql + "\"", CONNECTION_EXCEPTION.getSqlState(), e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Execute list of queries.
     * This method is used when using text batch statement and using rewriting (allowMultiQueries || rewriteBatchedStatements).
     * queries will be send to server according to max_allowed_packet size.
     *
     * @param mustExecuteOnMaster was intended to be launched on master connection
     * @param results             result object
     * @param queries             list of queries
     * @throws SQLException exception
     */
    public void executeBatchMultiple(boolean mustExecuteOnMaster, Results results, final List<String> queries)
            throws SQLException {

        cmdPrologue();

        String firstSql = null;
        int currentIndex = 0;
        int totalQueries = queries.size();
        SQLException exception = null;

        do {

            try {

                firstSql = queries.get(currentIndex++);

                if (totalQueries == 1) {
                    writer.send(firstSql, Packet.COM_QUERY);
                } else {
                    currentIndex = ComExecute.sendMultiple(writer, firstSql, queries, currentIndex);
                }

                getResult(results);

            } catch (SQLException queryException) {
                addQueryInfo(firstSql, queryException);
                if (!getOptions().continueBatchOnError) throw queryException;
                if (exception == null) exception = queryException;
            } catch (MaxAllowedPacketException e) {
                if (e.isMustReconnect()) connect();
                throw new SQLNonTransientConnectionException("Could not execute query", UNDEFINED_SQLSTATE.getSqlState(), e);
            } catch (IOException e) {
                throw new SQLNonTransientConnectionException("Could not execute query", CONNECTION_EXCEPTION.getSqlState(), e);
            } finally {
                writer.releaseBufferIfNotLogging();
            }

        } while (currentIndex < totalQueries);

        if (exception != null) throw exception;
    }

    /**
     * Specific execution for batch rewrite that has specific query for memory.
     *
     * @param mustExecuteOnMaster was intended to be launched on master connection
     * @param results             result
     * @param prepareResult       prepareResult
     * @param parameterList       parameters
     * @param rewriteValues       is rewritable flag
     * @throws SQLException exception
     */
    public void executeBatchRewrite(boolean mustExecuteOnMaster, Results results,
                                    final ClientPrepareResult prepareResult, List<ParameterHolder[]> parameterList,
                                    boolean rewriteValues) throws SQLException {
        cmdPrologue();

        ParameterHolder[] parameters;
        int currentIndex = 0;
        int totalParameterList = parameterList.size();

        try {

            do {

                parameters = parameterList.get(currentIndex++);
                currentIndex = ComExecute.sendRewriteCmd(writer, prepareResult.getQueryParts(), parameters, currentIndex,
                        prepareResult.getParamCount(), parameterList, rewriteValues);
                getResult(results);

            } while (currentIndex < totalParameterList);

        } catch (SQLException sqlEx) {
            throwErrorWithQuery(writer.buffer, sqlEx);
        } catch (MaxAllowedPacketException e) {
            if (e.isMustReconnect()) connect();
            throw new SQLNonTransientConnectionException("Could not execute query", UNDEFINED_SQLSTATE.getSqlState(), e);
        } catch (IOException e) {
            throw new SQLNonTransientConnectionException("Could not execute query", CONNECTION_EXCEPTION.getSqlState(), e);
        } finally {
            writer.releaseBufferIfNotLogging();
        }
    }

    /**
     * Execute Prepare if needed, and execute COM_STMT_EXECUTE queries in batch.
     *
     * @param mustExecuteOnMaster must normally be executed on master connection
     * @param serverPrepareResult prepare result. can be null if not prepared.
     * @param results             execution results
     * @param sql                 sql query if needed to be prepared
     * @param parametersList      parameter list
     * @return Prepare result
     * @throws SQLException if parameter error or connection error occur.
     */
    public ServerPrepareResult prepareAndExecutes(boolean mustExecuteOnMaster, ServerPrepareResult serverPrepareResult,
                                                  Results results, String sql, final List<ParameterHolder[]> parametersList)
            throws SQLException {

        cmdPrologue();

        return (ServerPrepareResult) new AbstractMultiSend(this, writer, results, serverPrepareResult, parametersList,true, sql) {
            @Override
            public void sendCmd(PacketOutputStream writer, Results results,
                                List<ParameterHolder[]> parametersList, List<String> queries, int paramCount, BulkStatus status,
                                PrepareResult prepareResult)
                    throws SQLException, IOException {

                ParameterHolder[] parameters = parametersList.get(status.sendCmdCounter);

                //validate parameter set
                if (parameters.length < paramCount) {
                    throw new SQLException("Parameter at position " + (paramCount - 1) + " is not set", "07004");
                }

                //send binary data in a separate stream
                for (int i = 0; i < paramCount; i++) {
                    if (parameters[i].isLongData()) {
                        new ComStmtLongData().send(writer, statementId, (short) i, parameters[i]);
                    }
                }

                writer.startPacket(0);
                ComStmtExecute.writeCmd(statementId, parameters, paramCount, parameterTypeHeader, writer);
                writer.finishPacketWithoutRelease(true);

            }

            @Override
            public SQLException handleResultException(SQLException qex, Results results,
                                                        List<ParameterHolder[]> parametersList, List<String> queries, int currentCounter,
                                                        int sendCmdCounter, int paramCount, PrepareResult prepareResult)
                    throws SQLException {
                return throwErrorWithQuery(parametersList, qex, (ServerPrepareResult) prepareResult);
            }

            @Override
            public int getParamCount() {
                return getPrepareResult() == null ? parametersList.get(0).length : ((ServerPrepareResult) getPrepareResult()).getParameters().length;
            }

            @Override
            public int getTotalExecutionNumber() {
                return parametersList.size();
            }

        }.executeBatch();

    }

    /**
     * Execute Prepare if needed, and execute COM_STMT_EXECUTE queries in batch.
     *
     * @param mustExecuteOnMaster must normally be executed on master connection
     * @param serverPrepareResult prepare result. can be null if not prepared.
     * @param results             execution results
     * @param sql                 sql query if needed to be prepared
     * @param parameters          parameters
     * @return Prepare result
     * @throws SQLException if parameter error or connection error occur.
     */
    public ServerPrepareResult prepareAndExecute(boolean mustExecuteOnMaster, ServerPrepareResult serverPrepareResult,
                                                  Results results, String sql, final ParameterHolder[] parameters)
            throws SQLException {

        cmdPrologue();

        int statementId = -1;
        int parameterCount = parameters.length;
        MariaDbType[] parameterTypeHeader = new MariaDbType[parameters.length];

        if (getOptions().cachePrepStmts) {

            String key = new StringBuilder(getDatabase()).append("-").append(sql).toString();
            serverPrepareResult = prepareStatementCache().get(key);
            if (serverPrepareResult != null && !serverPrepareResult.incrementShareCounter()) {
                //in cache but been de-allocated
                serverPrepareResult = null;
            }
            statementId = (serverPrepareResult == null) ? -1 : serverPrepareResult.getStatementId();

        }

        ComStmtPrepare comStmtPrepare;

        try {

            //add prepare sub-command
            if (serverPrepareResult == null) {

                comStmtPrepare = new ComStmtPrepare(this, sql);
                comStmtPrepare.send(writer);

                //read prepare result
                serverPrepareResult = comStmtPrepare.read(getPacketFetcher(), isEofDeprecated());
                statementId = serverPrepareResult.getStatementId();
                parameterCount = serverPrepareResult.getParameters().length;

            }

            if (serverPrepareResult != null && parameters.length < parameterCount) {
                throw new SQLException("Parameter at position " + (parameterCount) + " is not set", "07004");
            }

            //send binary data in a separate stream
            for (int i = 0; i < parameterCount; i++) {
                if (parameters[i].isLongData()) {
                    new ComStmtLongData().send(writer, statementId, (short) i, parameters[i]);
                }
            }

            writer.startPacket(0);
            ComStmtExecute.writeCmd(statementId, parameters, parameterCount, parameterTypeHeader, writer);
            writer.finishPacketWithoutRelease(true);

            //read result
            getResult(results);

            return serverPrepareResult;

        } catch (SQLException qex) {
            throw throwErrorWithQuery(parameters, qex, serverPrepareResult);
        } catch (MaxAllowedPacketException e) {
            if (e.isMustReconnect()) connect();
            throw new SQLNonTransientConnectionException("Could not execute query", UNDEFINED_SQLSTATE.getSqlState(), e);
        } catch (IOException e) {
            throw new SQLNonTransientConnectionException("Could not execute query", CONNECTION_EXCEPTION.getSqlState(), e);
        } finally {
            writer.releaseBufferIfNotLogging();
        }

    }

    /**
     * Execute a query that is already prepared.
     *
     * @param mustExecuteOnMaster must execute on master
     * @param serverPrepareResult prepare result
     * @param results             execution result
     * @param parameters          parameters
     * @throws SQLException exception
     */
    @Override
    public void executePreparedQuery(boolean mustExecuteOnMaster, ServerPrepareResult serverPrepareResult, Results results,
                                     ParameterHolder[] parameters)
            throws SQLException {

        cmdPrologue();

        try {

            int parameterCount = serverPrepareResult.getParameters().length;

            //send binary data in a separate stream
            for (int i = 0; i < parameterCount; i++) {
                if (parameters[i].isLongData()) {
                    new ComStmtLongData().send(writer, serverPrepareResult.getStatementId(), (short) i, parameters[i]);
                }
            }

            //send execute query
            new ComStmtExecute(serverPrepareResult.getStatementId(), parameters,
                    parameterCount, serverPrepareResult.getParameterTypeHeader())
                    .send(writer);

            getResult(results);

        } catch (SQLException qex) {
            throw throwErrorWithQuery(parameters, qex, serverPrepareResult);
        } catch (MaxAllowedPacketException e) {
            if (e.isMustReconnect()) connect();
            throw new SQLNonTransientConnectionException("Could not execute query", UNDEFINED_SQLSTATE.getSqlState(), e);
        } catch (IOException e) {
            throw new SQLNonTransientConnectionException("Could not execute query", CONNECTION_EXCEPTION.getSqlState(), e);
        } finally {
            writer.releaseBufferIfNotLogging();
        }
    }

    /**
     * Rollback transaction.
     */
    public void rollback() throws SQLException {

        cmdPrologue();

        lock.lock();
        try {

            if (inTransaction()) executeQuery("ROLLBACK");

        } catch (Exception e) {
            /* eat exception */
        } finally {
            lock.unlock();
        }
    }

    /**
     * Force release of prepare statement that are not used.
     * This method will be call when adding a new preparestatement in cache, so the packet can be send to server without
     * problem.
     *
     * @param statementId prepared statement Id to remove.
     * @return true if successfully released
     * @throws SQLException if connection exception.
     */
    public boolean forceReleasePrepareStatement(int statementId) throws SQLException {

        if (lock.tryLock()) {

            try {

                checkClose();

                try {
                    writer.closePrepare(statementId);
                    return true;
                } catch (IOException e) {
                    throw new SQLException("Could not deallocate query: " + e.getMessage(), CONNECTION_EXCEPTION.getSqlState(), e);
                }

            } finally {
                lock.unlock();
            }

        } else {
            //lock is used by another thread (bulk reading)
            statementIdToRelease = statementId;
        }

        return false;
    }

    /**
     * Force release of prepare statement that are not used.
     * This permit to deallocate a statement that cannot be release due to multi-thread use.
     *
     * @throws SQLException if connection occur
     */
    public void forceReleaseWaitingPrepareStatement() throws SQLException {

        if (statementIdToRelease != -1) {
            if (forceReleasePrepareStatement(statementIdToRelease)) {
                statementIdToRelease = -1;
            }
        }

    }

    @Override
    public boolean ping() throws SQLException {

        cmdPrologue();

        lock.lock();
        try {

            final SendPingPacket pingPacket = new SendPingPacket();
            pingPacket.send(writer);
            Buffer buffer = packetFetcher.getReusableBuffer();
            return buffer.getByteAt(0) == Packet.OK;

        } catch (IOException e) {
            throw new SQLException("Could not ping: " + e.getMessage(), CONNECTION_EXCEPTION.getSqlState(), e);
        } finally {
            lock.unlock();
        }
    }


    @Override
    public void setCatalog(final String database) throws SQLException {

        cmdPrologue();

        lock.lock();
        try {

            final SendChangeDbPacket packet = new SendChangeDbPacket(database);
            packet.send(writer);
            final Buffer buffer = packetFetcher.getReusableBuffer();

            if (buffer.getByteAt(0) == Packet.ERROR) {
                final ErrorPacket ep = new ErrorPacket(buffer);
                throw new SQLException("Could not select database '" + database + "' : " + ep.getMessage(),
                        ep.getSqlState(), ep.getErrorNumber());
            }

            this.database = database;

        } catch (IOException e) {
            throw new SQLException("Could not select database '" + database + "' :" + e.getMessage(), CONNECTION_EXCEPTION.getSqlState(), e);
        } finally {
            lock.unlock();
        }

    }

    /**
     * Cancels the current query - clones the current protocol and executes a query using the new connection.
     *
     * @throws SQLException never thrown
     * @throws IOException    if Host is not responding
     */
    @Override
    public void cancelCurrentQuery() throws SQLException, IOException {

        MasterProtocol copiedProtocol = new MasterProtocol(urlParser, new ReentrantLock());
        copiedProtocol.setHostAddress(getHostAddress());
        copiedProtocol.connect();
        //no lock, because there is already a query running that possessed the lock.
        copiedProtocol.executeQuery("KILL QUERY " + serverThreadId);
        copiedProtocol.close();

    }

    private void sendLocalFile(Results results, String fileName) throws IOException, SQLException {
        // Server request the local file (LOCAL DATA LOCAL INFILE)
        // We do accept general URLs, too. If the localInfileStream is
        // set, use that.
        int seq = 2;
        InputStream is;
        writer.setCompressSeqNo(2);
        if (localInfileInputStream == null) {

            if (!getUrlParser().getOptions().allowLocalInfile) {
                writer.writeEmptyPacket(seq++);
                packetFetcher.getReusableBuffer();
                throw new SQLException(
                        "Usage of LOCAL INFILE is disabled. To use it enable it via the connection property allowLocalInfile=true",
                        FEATURE_NOT_SUPPORTED.getSqlState());
            }

            //validate all defined interceptors
            ServiceLoader<LocalInfileInterceptor> loader = ServiceLoader.load(LocalInfileInterceptor.class);
            for (LocalInfileInterceptor interceptor : loader) {
                if (!interceptor.validate(fileName)) {
                    writer.writeEmptyPacket(seq++);
                    packetFetcher.getReusableBuffer();
                    throw new SQLException("LOCAL DATA LOCAL INFILE request to send local file named \""
                            + fileName + "\" not validated by interceptor \"" + interceptor.getClass().getName()
                            + "\"");
                }
            }

            try {
                URL url = new URL(fileName);
                is = url.openStream();
            } catch (IOException ioe) {
                try {
                    is = new FileInputStream(fileName);
                } catch (FileNotFoundException f) {
                    writer.writeEmptyPacket(seq++);
                    packetFetcher.getReusableBuffer();
                    throw new SQLException("Could not send file : " + f.getMessage(), "22000", f);
                }
            }

        } else {
            is = localInfileInputStream;
            localInfileInputStream = null;
        }

        writer.sendFile(is, seq);
        is.close();
        getResult(results);
    }

    /**
     * Get current autocommit status.
     *
     * @return autocommit status
     */
    @Override
    public boolean getAutocommit() {
        return ((serverStatus & ServerStatus.AUTOCOMMIT) != 0);
    }

    @Override
    public boolean inTransaction() {
        return ((serverStatus & ServerStatus.IN_TRANSACTION) != 0);
    }


    @Override
    public boolean hasMoreResults() {
        return moreResults;
    }

    public void closeExplicit() {
        this.explicitClosed = true;
        close();
    }


    /**
     * Deallocate prepare statement if not used anymore.
     *
     * @param serverPrepareResult allocation result
     * @throws SQLException if deallocation failed.
     */
    @Override
    public void releasePrepareStatement(ServerPrepareResult serverPrepareResult) throws SQLException {
        //If prepared cache is enable, the ServerPrepareResult can be shared in many PrepStatement,
        //so synchronised use count indicator will be decrement.
        serverPrepareResult.decrementShareCounter();

        //deallocate from server if not cached
        if (serverPrepareResult.canBeDeallocate()) {
            forceReleasePrepareStatement(serverPrepareResult.getStatementId());
        }
    }

    /**
     * Set max row retuen by a statement.
     *
     * @param max row number max value
     */
    public void setInternalMaxRows(int max) {
        if (maxRows != max) maxRows = max;
    }

    public int getMaxRows() {
        return maxRows;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        if (maxRows != max) {
            if (max == 0) {
                executeQuery("set @@SQL_SELECT_LIMIT=DEFAULT");
            } else {
                executeQuery("set @@SQL_SELECT_LIMIT=" + max);
            }
            maxRows = max;
        }
    }

    @Override
    public void setLocalInfileInputStream(InputStream inputStream) {
        this.localInfileInputStream = inputStream;
    }

    /**
     * Returns the connection timeout in milliseconds.
     *
     * @return the connection timeout in milliseconds.
     * @throws SocketException if there is an error in the underlying protocol, such as a TCP error.
     */
    @Override
    public int getTimeout() throws SocketException {
        return this.socket.getSoTimeout();
    }

    /**
     * Sets the connection timeout.
     *
     * @param timeout the timeout, in milliseconds
     * @throws SocketException if there is an error in the underlying protocol, such as a TCP error.
     */
    @Override
    public void setTimeout(int timeout) throws SocketException {
        lock.lock();
        try {
            this.getOptions().socketTimeout = timeout;
            this.socket.setSoTimeout(timeout);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Set transaction isolation.
     *
     * @param level transaction level.
     * @throws SQLException if transaction level is unknown
     */
    public void setTransactionIsolation(final int level) throws SQLException {
        cmdPrologue();
        lock.lock();
        try {
            String query = "SET SESSION TRANSACTION ISOLATION LEVEL";
            switch (level) {
                case Connection.TRANSACTION_READ_UNCOMMITTED:
                    query += " READ UNCOMMITTED";
                    break;
                case Connection.TRANSACTION_READ_COMMITTED:
                    query += " READ COMMITTED";
                    break;
                case Connection.TRANSACTION_REPEATABLE_READ:
                    query += " REPEATABLE READ";
                    break;
                case Connection.TRANSACTION_SERIALIZABLE:
                    query += " SERIALIZABLE";
                    break;
                default:
                    throw new SQLException("Unsupported transaction isolation level");
            }
            executeQuery(query);
            transactionIsolationLevel = level;
        } finally {
            lock.unlock();
        }
    }

    public int getTransactionIsolationLevel() {
        return transactionIsolationLevel;
    }

    private void checkClose() throws SQLException {
        if (!this.connected) throw new SQLException("Connection is close", "08000", 1220);
    }

    private SQLException addQueryInfo(String sql, SQLException sqlException) {
        if (getOptions().dumpQueriesOnException || sqlException.getErrorCode() == 1064) {
            if (options.maxQuerySizeToLog > 0 && sql.length() > options.maxQuerySizeToLog - 3) {
                sql = sql.substring(0, options.maxQuerySizeToLog - 3) + "...";
            }
            return new SQLException(sqlException.getMessage() + "\nQuery is : " + sql, sqlException.getSQLState(),
                    sqlException.getErrorCode(), sqlException.getCause());
        }
        return sqlException;
    }


    private void throwErrorWithQuery(ByteBuffer buffer, SQLException sqlEx) throws SQLException {
        if (getOptions().dumpQueriesOnException || sqlEx.getErrorCode() == 1064) {
            //log first maxQuerySizeToLog utf-8 characters
            String queryString;
            if (options.maxQuerySizeToLog == 0) {
                queryString = new String(buffer.array(), 5, buffer.limit());
            } else {
                queryString = new String(buffer.array(), 5, Math.min(buffer.limit() - 5, (options.maxQuerySizeToLog * 3)));
                if (queryString.length() > options.maxQuerySizeToLog - 3) {
                    queryString = queryString.substring(0, options.maxQuerySizeToLog - 3) + "...";
                }
            }
            throw addQueryInfo(queryString, sqlEx);
        }
        throw sqlEx;
    }

    private SQLException throwErrorWithQuery(ParameterHolder[] parameters, SQLException sqlEx, PrepareResult serverPrepareResult)
            throws SQLException {
        if (sqlEx.getCause() instanceof SocketTimeoutException) {
            return new SQLException("Connection timed out", CONNECTION_EXCEPTION.getSqlState(), sqlEx);
        }
        if (getOptions().dumpQueriesOnException || sqlEx.getErrorCode() == 1064) {
            String sql = serverPrepareResult.getSql();
            if (serverPrepareResult.getParamCount() > 0) {
                sql += ", parameters [";
                if (parameters.length > 0) {
                    for (int i = 0; i < Math.min(parameters.length, serverPrepareResult.getParamCount()); i++) {
                        sql += parameters[i].toString() + ",";
                    }
                    sql = sql.substring(0, sql.length() - 1);
                }
                sql += "]";
            }

            String message = sqlEx.getMessage();
            if (options.maxQuerySizeToLog != 0 && sql.length() > options.maxQuerySizeToLog - 3) {
                message += "\nQuery is: " + sql.substring(0, options.maxQuerySizeToLog - 3) + "...";
            } else {
                message += "\nQuery is: " + sql;
            }
            return new SQLException(message, sqlEx.getSQLState(), sqlEx.getErrorCode(), sqlEx.getCause());
        }
        return sqlEx;
    }

    private SQLException throwErrorWithQuery(List<ParameterHolder[]> parameterList, SQLException sqlEx, ServerPrepareResult serverPrepareResult)
            throws SQLException {
        if (getOptions().dumpQueriesOnException || sqlEx.getErrorCode() == 1064) {
            String querySql = serverPrepareResult.getSql();

            if (serverPrepareResult.getParameters().length > 0) {
                querySql += ", parameters ";
                for (int paramNo = 0; paramNo < parameterList.size(); paramNo++) {
                    ParameterHolder[] parameters = parameterList.get(paramNo);
                    querySql += "[";
                    if (parameters.length > 0) {
                        for (int i = 0; i < parameters.length; i++) {
                            querySql += parameters[i].toString() + ",";
                        }
                        querySql = querySql.substring(0, querySql.length() - 1);
                    }
                    if (options.maxQuerySizeToLog > 0 && querySql.length() > options.maxQuerySizeToLog) {
                        break;
                    } else {
                        querySql += "],";
                    }
                }
                querySql = querySql.substring(0, querySql.length() - 1);
            }

            String message = sqlEx.getMessage();
            if (options.maxQuerySizeToLog != 0 && querySql.length() > options.maxQuerySizeToLog - 3) {
                message += "\nQuery is: " +  querySql.substring(0, options.maxQuerySizeToLog - 3) + "...";
            } else {
                message += "\nQuery is: " +  querySql;
            }
            return new SQLException(message, sqlEx.getSQLState(), sqlEx.getErrorCode(), sqlEx.getCause());
        }
        return sqlEx;
    }

    @Override
    public void getResult(Results results) throws SQLException {

        readPacket(results);

        //load additional results
        while (moreResults) {
            readPacket(results);
        }

    }

    /**
     * Read server response packet.
     *
     * @see <a href="https://mariadb.com/kb/en/mariadb/4-server-response-packets/">server response packets</a>
     *
     * @param results result object
     * @throws SQLException if sub-result connection fail
     */
    public void readPacket(Results results) throws SQLException {
        Buffer buffer;
        try {
            buffer = packetFetcher.getReusableBuffer();
        } catch (IOException e) {
            try {
                if (writer != null) {
                    writer.writeEmptyPacket(packetFetcher.getLastPacketSeq() + 1);
                    packetFetcher.getReusableBuffer();
                }
            } catch (IOException ee) {
            }
            throw new SQLException("Could not read packet: " + e.getMessage(), CONNECTION_EXCEPTION.getSqlState(), e);
        }

        switch (buffer.getByteAt(0)) {

            //*********************************************************************************************************
            //* OK response
            //*********************************************************************************************************
            case Packet.OK:
                readOkPacket(buffer, results);
                break;

            //*********************************************************************************************************
            //* ERROR response
            //*********************************************************************************************************
            case Packet.ERROR:
                throw readErrorPacket(buffer, results);

            //*********************************************************************************************************
            //* LOCAL INFILE response
            //*********************************************************************************************************
            case Packet.LOCAL_INFILE:
                readLocalInfilePacket(buffer, results);
                break;

            //*********************************************************************************************************
            //* ResultSet
            //*********************************************************************************************************
            default:
                readResultSet(buffer, results);
                break;

        }

    }

    /**
     * Read OK_Packet.
     *
     * @see <a href="https://mariadb.com/kb/en/mariadb/ok_packet/">OK_Packet</a>
     *
     * @param buffer current buffer
     * @param results result object
     * @throws SQLException if sub-result connection fail
     */
    public void readOkPacket(Buffer buffer, Results results) throws SQLException {
        buffer.skipByte(); //fieldCount
        final int updateCount = (int) buffer.getLengthEncodedBinary();
        final long insertId = buffer.getLengthEncodedBinary();
        serverStatus = buffer.readShort();
        this.hasWarnings = (buffer.readShort() > 0);
        this.moreResults = ((serverStatus & ServerStatus.MORE_RESULTS_EXISTS) != 0);

        if ((serverCapabilities & MariaDbServerCapabilities.CLIENT_SESSION_TRACK) != 0
                && (serverStatus & ServerStatus.SERVER_SESSION_STATE_CHANGED) != 0) {
            buffer.skipLengthEncodedBytes(); //info
            Buffer sessionStateInfoBuffer = new Buffer(buffer.getLengthEncodedBytes());
            while (sessionStateInfoBuffer.remaining() > 0) {
                if (sessionStateInfoBuffer.readByte() == SessionChangeType.SESSION_TRACK_SCHEMA) {
                    //schema has changed
                    sessionStateInfoBuffer.getLengthEncodedBinary(); //entity length
                    database = sessionStateInfoBuffer.readLengthEncodedString(StandardCharsets.UTF_8);
                } else {
                    sessionStateInfoBuffer.skipLengthEncodedBytes();
                }
            }
        }
        results.addStats(updateCount, insertId, moreResults);
    }


    /**
     * Read ERR_Packet.
     *
     * @see <a href="https://mariadb.com/kb/en/mariadb/err_packet/">ERR_Packet</a>
     *
     * @param buffer current buffer
     * @param results result object
     * @return SQLException if sub-result connection fail
     */
    public SQLException readErrorPacket(Buffer buffer, Results results) {
        this.moreResults = false;
        this.hasWarnings = false;
        buffer.skipByte();
        int errorNumber = buffer.readShort();
        String message;
        String sqlState;
        if (buffer.readByte() == '#') {
            sqlState = new String(buffer.readRawBytes(5));
            message = buffer.readString(StandardCharsets.UTF_8);
        } else {
            // Pre-4.1 message, still can be output in newer versions (e.g with 'Too many connections')
            buffer.position -= 1;
            message = new String(buffer.buf, buffer.position, buffer.limit - buffer.position, StandardCharsets.UTF_8);
            sqlState = "HY000";
        }
        results.addStatsError(false);
        removeActiveStreamingResult();
        return new SQLException(message, sqlState, errorNumber);
    }

    /**
     * Read Local_infile Packet.
     *
     * @see <a href="https://mariadb.com/kb/en/mariadb/local_infile-packet/">local_infile packet</a>
     *
     * @param buffer current buffer
     * @param results result object
     * @throws SQLException if sub-result connection fail
     */
    public void readLocalInfilePacket(Buffer buffer, Results results) throws SQLException {

        buffer.getLengthEncodedBinary(); //field count

        String fileName = buffer.readString(StandardCharsets.UTF_8);

        try {
            sendLocalFile(results, fileName);
        } catch (MaxAllowedPacketException e) {
            if (e.isMustReconnect()) connect();
            try {
                if (writer != null) {
                    writer.writeEmptyPacket(packetFetcher.getLastPacketSeq() + 1);
                    packetFetcher.getReusableBuffer();
                }
            } catch (IOException ee) {
            }
            throw new SQLNonTransientConnectionException("Could not send query: " + e.getMessage(), UNDEFINED_SQLSTATE.getSqlState(), e);
        } catch (IOException e) {
            try {
                if (writer != null) {
                    writer.writeEmptyPacket(packetFetcher.getLastPacketSeq() + 1);
                    packetFetcher.getReusableBuffer();
                }
            } catch (IOException ee) {
            }
            throw new SQLNonTransientConnectionException("Could not read resultSet: " + e.getMessage(), CONNECTION_EXCEPTION.getSqlState(), e);
        }
    }

    /**
     * Read ResultSet Packet.
     *
     * @see <a href="https://mariadb.com/kb/en/mariadb/resultset/">resultSet packets</a>
     *
     * @param buffer current buffer
     * @param results result object
     * @throws SQLException if sub-result connection fail
     */
    public void readResultSet(Buffer buffer, Results results) throws SQLException {
        this.hasWarnings = false;
        this.moreResults = false;
        long fieldCount = buffer.getLengthEncodedBinary();

        try {

            //read columns information's
            ColumnInformation[] ci = new ColumnInformation[(int) fieldCount];
            for (int i = 0; i < fieldCount; i++) {
                ci[i] = new ColumnInformation(packetFetcher.getPacket());
            }

            //read EOF packet
            //EOF status is mandatory because :
            // - Call query will have an callable resultSet for OUT parameters
            //   -> this resultSet must be identified and not listed in JDBC statement.getResultSet()
            // - after a callable resultSet, a OK packet is send, but mysql does send the  a bad "more result flag",
            //so this flag is absolutely needed !
            boolean callableResult = false;
            if (!isEofDeprecated()) {
                Buffer bufferEof = packetFetcher.getReusableBuffer();
                if (bufferEof.readByte() != Packet.EOF) {
                    throw new SQLException("Packets out of order when reading field packets, expected was EOF stream. "
                            + "Packet contents (hex) = " + Utils.hexdump(bufferEof.buf, options.maxQuerySizeToLog, 0, buffer.position));
                }
                buffer.skipBytes(2); //Skip warningCount
                callableResult = (buffer.readShort() & ServerStatus.PS_OUT_PARAMETERS) != 0;
            }

            //read resultSet
            MariaSelectResultSet mariaSelectResultset = new MariaSelectResultSet(ci, results, this, packetFetcher, callableResult);
            results.addResultSet(mariaSelectResultset, moreResults);

        } catch (IOException e) {
            throw new SQLException("Could not read result set: " + e.getMessage(), CONNECTION_EXCEPTION.getSqlState(), e);
        }
    }

    public void prologProxy(ServerPrepareResult serverPrepareResult, int maxRows, boolean hasProxy,
                            MariaDbConnection connection, MariaDbStatement statement) throws SQLException {
        prolog(maxRows, hasProxy, connection, statement);
    }

    /**
     * Preparation before command.
     *
     * @param maxRows         query max rows
     * @param hasProxy        has proxy
     * @param connection      current connection
     * @param statement       current statement
     * @throws SQLException if any error occur.
     */
    public void prolog(int maxRows, boolean hasProxy, MariaDbConnection connection, MariaDbStatement statement)
            throws SQLException {
        if (explicitClosed) {
            throw new SQLException("execute() is called on closed connection");
        }
        //old failover handling
        if (!hasProxy) {
            if (shouldReconnectWithoutProxy()) {
                try {
                    connectWithoutProxy();
                } catch (SQLException qe) {
                    ExceptionMapper.throwException(qe, connection, statement);
                }
            }
        }

        try {
            setMaxRows(maxRows);
        } catch (SQLException qe) {
            ExceptionMapper.throwException(qe, connection, statement);
        }

        connection.reenableWarnings();
    }

    public ServerPrepareResult addPrepareInCache(String key, ServerPrepareResult serverPrepareResult) {
        return serverPrepareStatementCache.put(key, serverPrepareResult);
    }


    private void cmdPrologue() throws SQLException {

        //load active result if any so buffer are clean for next query
        if (activeStreamingResult != null) {
            activeStreamingResult.loadFully(false, this);
            activeStreamingResult = null;
        }

        this.moreResults = false;

        if (!this.connected) throw new SQLException("Connection is close", "08000", 1220);

    }

}
