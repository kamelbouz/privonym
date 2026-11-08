package com.privonym.repository.oracle;
import java.sql.Connection;
import java.sql.SQLException;
@FunctionalInterface
public interface ExecutionControlConnections {Connection open()throws SQLException;}
