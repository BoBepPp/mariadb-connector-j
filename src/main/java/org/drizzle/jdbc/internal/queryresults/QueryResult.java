package org.drizzle.jdbc.internal.queryresults;

import org.drizzle.jdbc.internal.packet.FieldPacket;
import org.drizzle.jdbc.internal.ValueObject;

import java.util.List;

/**
 .
 * User: marcuse
 * Date: Feb 5, 2009
 * Time: 10:20:03 PM

 */
public interface QueryResult {
    public ResultSetType getResultSetType();
     public void close();
    short getWarnings();
    String getMessage();

    List<ColumnInformation> getColumnInformation();

    int getRows();
}