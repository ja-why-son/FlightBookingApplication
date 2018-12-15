import java.io.FileInputStream;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.lang.Math;
import java.lang.Integer;

/**
 * Runs queries against a back-end database
 */
public class Query
{
  private String configFilename;
  private Properties configProps = new Properties();

  private String jSQLDriver;
  private String jSQLUrl;
  private String jSQLUser;
  private String jSQLPassword;

  // DB Connection
  private Connection conn;

  // Logged In User
  private String username; // customer username is unique

  // store itineraries' fid
  private ArrayList<String> itineraries;

  // transactions
  private static final String BEGIN_TRANSACTION_SQL = "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE; BEGIN TRANSACTION;";
  private PreparedStatement beginTransactionStatement;

  private static final String COMMIT_SQL = "COMMIT TRANSACTION";
  private PreparedStatement commitTransactionStatement;

  private static final String ROLLBACK_SQL = "ROLLBACK TRANSACTION";
  private PreparedStatement rollbackTransactionStatement;

  // Prepared Statements
  private static final String CREATE_USER = "INSERT INTO UserInfo (username, password, balance) VALUES (?,?,?)";
  private PreparedStatement createUserStatement;

  private static final String LOGIN_SEARCH = "SELECT password FROM UserInfo WHERE username = ?";
  private PreparedStatement loginSearchStatement;

  private static final String SEARCH_DIRECT_FLIGHT = "INSERT INTO Itineraries (flight_1, flight_2, total_time) SELECT TOP (?) fid, 0, actual_time FROM Flights WHERE origin_city = ? AND dest_city = ? AND day_of_month = ? AND canceled = 0 ORDER BY actual_time ASC, fid ASC";
  private PreparedStatement searchDirectStatement;

  private static final String SEARCH_ONE_STOP_FLIGHT = "INSERT INTO Itineraries (flight_1, flight_2, total_time) SELECT TOP (?) f1.fid AS fid1, f2.fid AS fid2, f1.actual_time + f2.actual_time AS total_time FROM Flights f1, Flights f2 WHERE f1.origin_city = ? AND f1.dest_city = f2.origin_city AND f2.dest_city = ? AND f1.day_of_month = f2.day_of_month AND f1.day_of_month = ? AND f1.canceled = 0  AND f2.canceled = 0 ORDER BY total_time ASC, f1.fid ASC, f2.fid ASC";
  private PreparedStatement searchOneStopStatement;

  private static final String GET_INFO = "SELECT fid, day_of_month, carrier_id, flight_num, origin_city, dest_city, actual_time, capacity, price FROM Flights WHERE fid = ?";
  private PreparedStatement getInfoStatement;

  private static final String SORT = "SELECT * FROM Itineraries ORDER BY total_time ASC, flight_1 ASC, flight_2 ASC";
  private PreparedStatement sortStatement;

  private static final String MAKE_RESERVATION = "INSERT INTO Reserve VALUES (?, ?, ?, ?, ?, ?, ?)";
  private PreparedStatement makeReservationStatement;

  private static final String UPDATE_RESERVE = "UPDATE Reserve SET paid = ? WHERE reserve_id = ?";
  private PreparedStatement updateReserveStatement;

  private static final String FIND_RESERVATION = "SELECT * FROM Reserve WHERE username = ? AND reserve_id = ? AND paid = 0";
  private PreparedStatement findStatement;

  private static final String GET_FIDS = "SELECT fid1, fid2 FROM Reserve WHERE reserve_id = ?";
  private PreparedStatement getFidsStatement;

  private static final String VERIFY_RESERVATION = "SELECT username, paid FROM Reserve WHERE reserve_id = ?";
  private PreparedStatement verifyReserveStatement;

  private static final String UPDATE_BALANCE = "UPDATE UserInfo SET balance = (SELECT balance FROM UserInfo WHERE username = ?) + ? WHERE username = ?";
  private PreparedStatement updateBalanceStatement;

  private static final String CANCEL_RESERVE = "UPDATE Reserve SET username = ' ', fid1 = 0, fid2 = 0, flight_date = 0, paid = 0 WHERE reserve_id = ?";
  private PreparedStatement cancelReserveStatement;

  public Query(String configFilename)
  {
    this.configFilename = configFilename;
  }

  /* Connection code to SQL Azure.  */
  public void openConnection() throws Exception
  {
    configProps.load(new FileInputStream(configFilename));

    jSQLDriver = configProps.getProperty("flightservice.jdbc_driver");
    jSQLUrl = configProps.getProperty("flightservice.url");
    jSQLUser = configProps.getProperty("flightservice.sqlazure_username");
    jSQLPassword = configProps.getProperty("flightservice.sqlazure_password");

    /* load jdbc drivers */
    Class.forName(jSQLDriver).newInstance();

    /* open connections to the flights database */
    conn = DriverManager.getConnection(jSQLUrl, // database
            jSQLUser, // user
            jSQLPassword); // password

    conn.setAutoCommit(true); //by default automatically commit after each statement

    conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
  }

  public void closeConnection() throws Exception
  {
    conn.close();
  }

  /**
   * Clear the data in any custom tables created. Do not drop any tables and do not
   * clear the flights table. You should clear any tables you use to store reservations
   * and reset the next reservation ID to be 1.
   */
  public void clearTables () throws Exception
  {
    String clearUser = "TRUNCATE TABLE UserInfo";
    String clearReservation = "TRUNCATE TABLE Reserve";
    String clearItineraries = "TRUNCATE TABLE Itineraries";
    String initializeReserve = "INSERT INTO Reserve (reserve_id, next_id) VALUES (0, 1)";
    Statement clearStatement = conn.createStatement();
    clearStatement.executeUpdate(clearUser);
    clearStatement.executeUpdate(clearReservation);
    clearStatement.executeUpdate(clearItineraries);
    clearStatement.executeUpdate(initializeReserve);
  }

  /**
   * prepare all the SQL statements in this method.
   * "preparing" a statement is almost like compiling it.
   * Note that the parameters (with ?) are still not filled in
   */
  public void prepareStatements() throws Exception
  {
    beginTransactionStatement = conn.prepareStatement(BEGIN_TRANSACTION_SQL);
    commitTransactionStatement = conn.prepareStatement(COMMIT_SQL);
    rollbackTransactionStatement = conn.prepareStatement(ROLLBACK_SQL);
    createUserStatement = conn.prepareStatement(CREATE_USER);
    loginSearchStatement = conn.prepareStatement(LOGIN_SEARCH);
    searchDirectStatement = conn.prepareStatement(SEARCH_DIRECT_FLIGHT);
    searchOneStopStatement = conn.prepareStatement(SEARCH_ONE_STOP_FLIGHT);
    getInfoStatement = conn.prepareStatement(GET_INFO);
    sortStatement = conn.prepareStatement(SORT);
    makeReservationStatement = conn.prepareStatement(MAKE_RESERVATION);
    updateReserveStatement = conn.prepareStatement(UPDATE_RESERVE);
    findStatement = conn.prepareStatement(FIND_RESERVATION);
    getFidsStatement = conn.prepareStatement(GET_FIDS);
    verifyReserveStatement = conn.prepareStatement(VERIFY_RESERVATION);
    updateBalanceStatement = conn.prepareStatement(UPDATE_BALANCE);
    cancelReserveStatement = conn.prepareStatement(CANCEL_RESERVE);
  }

  /**
   * Takes a user's username and password and attempts to log the user in.
   *
   * @param username
   * @param password
   *
   * @return If someone has already logged in, then return "User already logged in\n"
   * For all other errors, return "Login failed\n".
   *
   * Otherwise, return "Logged in as [username]\n".
   */
  public String transaction_login(String username, String password)
  {
    if (this.username == null){
      try {
        loginSearchStatement.clearParameters();
        loginSearchStatement.setString(1, username);
        ResultSet result = loginSearchStatement.executeQuery();
        result.next();
        String result_password = result.getString("password");
        result.close();
        if (password.equalsIgnoreCase(result_password)){
          this.username = username;
          return "Logged in as " + username + "\n";
        } else {
          return "Login failed\n";
        }
      } catch (SQLException e){
        return "Login failed\n";
      }
    }
    return "User already logged in\n";
  }

  /**
   * Implement the create user function.
   *
   * @param username new user's username. User names are unique the system.
   * @param password new user's password.
   * @param initAmount initial amount to deposit into the user's account, should be >= 0 (failure otherwise).
   *
   * @return either "Created user {@code username}\n" or "Failed to create user\n" if failed.
   */
  public String transaction_createCustomer (String username, String password, int initAmount) {
    try {
      createUserStatement.clearParameters();
      createUserStatement.setString(1, username);
      createUserStatement.setString(2, password);
      createUserStatement.setInt(3, initAmount);
      createUserStatement.executeUpdate();
    } catch (SQLException e) {
      return "Failed to create user" + "\n";
    }
    return "Created user " + username + "\n";
  }

  /**
   * Implement the search function.
   *
   * Searches for flights from the given origin city to the given destination
   * city, on the given day of the month. If {@code directFlight} is true, it only
   * searches for direct flights, otherwise is searches for direct flights
   * and flights with two "hops." Only searches for up to the number of
   * itineraries given by {@code numberOfItineraries}.
   *
   * The results are sorted based on total flight time.
   *
   * @param originCity
   * @param destinationCity
   * @param directFlight if true, then only search for direct flights, otherwise include indirect flights as well
   * @param dayOfMonth
   * @param numberOfItineraries number of itineraries to return
   *
   * @return If no itineraries were found, return "No flights match your selection\n".
   * If an error occurs, then return "Failed to search\n".
   *
   * Otherwise, the sorted itineraries printed in the following format:
   *
   * Itinerary [itinerary number]: [number of flights] flight(s), [total flight time] minutes\n
   * [first flight in itinerary]\n
   * ...
   * [last flight in itinerary]\n
   *
   * Each flight should be printed using the same format as in the {@code Flight} class. Itinerary numbers
   * in each search should always start from 0 and increase by 1.
   *
   * @see Flight#toString()
   */
  public String transaction_search(String originCity, String destinationCity, boolean directFlight, int dayOfMonth, int numberOfItineraries) {
    try {
      beginTransaction();
      String clearItineraries = "TRUNCATE TABLE Itineraries";
      Statement clearStatement = conn.createStatement();
      clearStatement.executeUpdate(clearItineraries);
      searchDirectFlight(originCity, destinationCity, dayOfMonth, numberOfItineraries);
      if (!directFlight) {
        String checkCount = "SELECT COUNT(*) AS num FROM Itineraries";
        Statement checkCountStatement = conn.createStatement();
        ResultSet count = checkCountStatement.executeQuery(checkCount);
        count.next();
        int countResult = count.getInt("num");
        count.close();
        if (numberOfItineraries - countResult > 0){
          searchOneStopFlight(originCity, destinationCity, dayOfMonth, numberOfItineraries - countResult);
        }
      }
      commitTransaction();
    } catch (SQLException e) {
      return "Failed to search\n";
    }
    String answer = parseItineraries();
    if (answer.isEmpty()) {
      return "No flights match your selection\n";
    }
    return answer;
  }

  // find direct flight and add it to itineraries table
  private void searchDirectFlight(String originCity, String destinationCity, int dayOfMonth, int numberOfItineraries) {
    try {
      searchDirectStatement.clearParameters();
      searchDirectStatement.setInt(1, numberOfItineraries);
      searchDirectStatement.setString(2, originCity);
      searchDirectStatement.setString(3, destinationCity);
      searchDirectStatement.setInt(4, dayOfMonth);
      searchDirectStatement.executeUpdate();
    } catch (SQLException e) {}
  }

  // find indirect flight and add it to tineraries table
  private void searchOneStopFlight(String originCity, String destinationCity, int dayOfMonth, int number) {
    try {
      searchOneStopStatement.clearParameters();
      searchOneStopStatement.setInt(1, number);
      searchOneStopStatement.setString(2, originCity);
      searchOneStopStatement.setString(3, destinationCity);
      searchOneStopStatement.setInt(4, dayOfMonth);
      searchOneStopStatement.executeUpdate();
    } catch (SQLException e) {}
  }

  // put the fid into arraylist and create the itineraries result
  private String parseItineraries() {
    String answer = "";
    itineraries = new ArrayList<String>();
    try {
      ResultSet flightBasicInfo = sortStatement.executeQuery();
      int intNum = 0;
      while (flightBasicInfo.next()) {
        int flight1 = flightBasicInfo.getInt("flight_1");
        int flight2 = flightBasicInfo.getInt("flight_2");
        int time = flightBasicInfo.getInt("total_time");
        if (flight2 == 0) {
          answer += "Itinerary " + intNum + ": 1 flight(s), " + time + " minutes\n";
          answer += getFlightDetails(flight1);
          itineraries.add(flight1 + " 0");
          intNum++;
        } else {
          answer += "Itinerary " + intNum + ": 2 flight(s), " + time + " minutes\n";
          answer += getFlightDetails(flight1);
          answer += getFlightDetails(flight2);
          itineraries.add(flight1 + " " + flight2);
          intNum++;
        }
      }
    } catch (SQLException e) {}
    return answer;
  }

  // get the details of the flight
  private String getFlightDetails(int fid) {
    if (fid == 0) {
      return "";
    }
    String details = "";
    try {
      getInfoStatement.clearParameters();
      getInfoStatement.setInt(1, fid);
      ResultSet info = getInfoStatement.executeQuery();
      info.next();
      details += "ID: " + info.getInt("fid") + " Day: " + info.getInt("day_of_month") + " Carrier: " + info.getString("carrier_id") +
                " Number: " + info.getInt("flight_num") + " Origin: " + info.getString("origin_city") + " Dest: " + info.getString("dest_city") + " Duration: " + info.getInt("actual_time") +
                " Capacity: " + info.getInt("capacity") + " Price: " + info.getInt("price") + "\n";
      info.close();
    } catch (SQLException e) {}
    return details;
  }

  /**
   * Implements the book itinerary function.
   *
   * @param itineraryId ID of the itinerary to book. This must be one that is returned by search in the current session.
   *
   * @return If the user is not logged in, then return "Cannot book reservations, not logged in\n".
   * If try to book an itinerary with invalid ID, then return "No such itinerary {@code itineraryId}\n".
   * If the user already has a reservation on the same day as the one that they are trying to book now, then return
   * "You cannot book two flights in the same day\n".
   * For all other errors, return "Booking failed\n".
   *
   * And if booking succeeded, return "Booked flight(s), reservation ID: [reservationId]\n" where
   * reservationId is a unique number in the reservation system that starts from 1 and increments by 1 each time a
   * successful reservation is made by any user in the system.
   */
  public String transaction_book(int itineraryId) {
    if (username == null) {
      return "Cannot book reservations, not logged in\n";
    } else if (itineraries == null || itineraryId >= itineraries.size()) {
      return "No such itinerary " + itineraryId + "\n";
    }
    String[] fids = itineraries.get(itineraryId).split(" ", 2);
    int fidOne = Integer.parseInt(fids[0]);
    int fidTwo = Integer.parseInt(fids[1]);
    int reservationId = 0;
    try {
      beginTransaction();
      if (checkDate(fidOne)) {
        rollbackTransaction();
        return "You cannot book two flights in the same day\n";
      }
      if (fidTwo == 0) {
        if (checkCapacity(fidOne)) {
          rollbackTransaction();
          return "Booking failed\n";
        }
      } else {
        if (checkCapacity(fidOne) || checkCapacity(fidTwo)) {
          rollbackTransaction();
          return "Booking failed\n";
        }
      }
      reservationId = makeReservation(fidOne, fidTwo);
      commitTransaction();
    } catch (SQLException e) {
      return "Booking failed\n";
    }
    return "Booked flight(s), reservation ID: " + reservationId + "\n";
  }

  // return the reservation id so it can be used later
  private int makeReservation(int fid1, int fid2) {
    int currentId = 0;
    int date = 0;
    String getId = "SELECT MAX(next_id) AS nextId FROM Reserve";
    String getDate = "SELECT day_of_month FROM Flights WHERE fid = " + fid1;
    try {
      Statement getIdStatement = conn.createStatement();
      ResultSet nextIdSet = getIdStatement.executeQuery(getId);
      nextIdSet.next();
      currentId = nextIdSet.getInt("nextId");
      nextIdSet.close();
      Statement getDateStatement = conn.createStatement();
      ResultSet nextDaySet = getDateStatement.executeQuery(getDate);
      nextDaySet.next();
      date = nextDaySet.getInt("day_of_month");
      nextDaySet.close();
      makeReservationStatement.clearParameters();
      makeReservationStatement.setInt(1, currentId);
      makeReservationStatement.setString(2, username);
      makeReservationStatement.setInt(3, fid1);
      makeReservationStatement.setInt(4, fid2);
      makeReservationStatement.setInt(5, date);
      makeReservationStatement.setInt(6, 0);
      makeReservationStatement.setInt(7, currentId + 1);
      makeReservationStatement.executeUpdate();
    } catch (SQLException e) {}
    return currentId;
  }

  // return true if there's date conflict
  private boolean checkDate(int fid) {
    // use username field to access username
    String checkDateQuery = "SELECT reserve_id FROM Reserve WHERE username = '" + username + "' AND flight_date = (SELECT day_of_month FROM Flights WHERE fid = " + fid + ")";
    try {
      Statement checkDateStatement = conn.createStatement();
      ResultSet result = checkDateStatement.executeQuery(checkDateQuery);
      result.next();
      result.getInt("reserve_id");
    } catch (SQLException e) {
      return false;
    }
    return true;
  }

  // return true if there's capacity conflict
  private boolean checkCapacity(int fid) {
    String checkCapacityQueryOne = "SELECT count(*) AS reserved FROM Reserve WHERE fid1 = " + fid + " OR fid2 = " + fid;
    String checkCapacityQueryTwo = "SELECT capacity FROM Flights WHERE fid = " + fid;
    int reserved = 0;
    int capacity = 0;
    try {
      Statement getReserved = conn.createStatement();
      ResultSet resultOne = getReserved.executeQuery(checkCapacityQueryOne);
      resultOne.next();
      reserved = resultOne.getInt("reserved");
      resultOne.close();
      /*****************/
      Statement getCapacity = conn.createStatement();
      ResultSet resultTwo = getCapacity.executeQuery(checkCapacityQueryTwo);
      resultTwo.next();
      capacity = resultTwo.getInt("capacity");
      resultTwo.close();
    } catch (SQLException e) {}
    return capacity <= reserved;
  }

  /**
   * Implements the reservations function.
   *
   * @return If no user has logged in, then return "Cannot view reservations, not logged in\n"
   * If the user has no reservations, then return "No reservations found\n"
   * For all other errors, return "Failed to retrieve reservations\n"
   *
   * Otherwise return the reservations in the following format:
   *
   * Reservation [reservation ID] paid: [true or false]:\n"
   * [flight 1 under the reservation]
   * [flight 2 under the reservation]
   * Reservation [reservation ID] paid: [true or false]:\n"
   * [flight 1 under the reservation]
   * [flight 2 under the reservation]
   * ...
   *
   * Each flight should be printed using the same format as in the {@code Flight} class.
   *
   * @see Flight#toString()
   */
  public String transaction_reservations() {
    if (username == null) {
      return "Cannot view reservations, not logged in\n";
    }
    String getReservation = "SELECT reserve_id, paid, fid1, fid2 FROM Reserve WHERE username = '" + username + "'";
    String answer = "";
    try {
      Statement getReservationStatement = conn.createStatement();
      ResultSet result = getReservationStatement.executeQuery(getReservation);
      while (result.next()) {
        answer += "Reservation " + result.getInt("reserve_id") + " paid: " + trueOrFalse(result.getInt("paid")) + ":\n";
        answer += getFlightDetails(result.getInt("fid1"));
        answer += getFlightDetails(result.getInt("fid2"));
      }
    } catch (SQLException e) {
        return "Failed to retrieve reservations\n";
    }
    if (answer.equals("")) {
      return "No reservations found\n";
    }
    return answer;
  }

  // return "true" if passed in 1, or return "false"
  private String trueOrFalse(int num) {
    if (num == 1) {
      return "true";
    }
    return "false";
  }

  /**
   * Implements the pay function.
   *
   * @param reservationId the reservation to pay for.
   *
   * @return If no user has logged in, then return "Cannot pay, not logged in\n"
   * If the reservation is not found / not under the logged in user's name, then return
   * "Cannot find unpaid reservation [reservationId] under user: [username]\n"
   * If the user does not have enough money in their account, then return
   * "User has only [balance] in account but itinerary costs [cost]\n"
   * For all other errors, return "Failed to pay for reservation [reservationId]\n"
   *
   * If successful, return "Paid reservation: [reservationId] remaining balance: [balance]\n"
   * where [balance] is the remaining balance in the user's account.
   */
  public String transaction_pay (int reservationId) {
    int balance = 0;
    int total = 0;
    try {
      if (username == null) {
        return "Cannot pay, not logged in\n";
      }
      beginTransaction();
      if (findReservation(reservationId)) {
        rollbackTransaction();
        return "Cannot find unpaid reservation " + reservationId + " under user: " + username + "\n";
      }
      String getBalance = "SELECT balance FROM UserInfo WHERE username = '" + username + "'";
      total = getSum(reservationId);
      Statement getBalanceStatement = conn.createStatement();
      ResultSet balanceSet = getBalanceStatement.executeQuery(getBalance);
      balanceSet.next();
      balance = balanceSet.getInt("balance");
      balanceSet.close();
      if (balance < total) {
        rollbackTransaction();
        return "User has only " + balance + " in account but itinerary costs " + total + "\n";
      }
      payItinerary(reservationId, balance, total);
      commitTransaction();
    } catch (SQLException e) {
      return "Failed to pay for reservation " + reservationId + "\n";
    }
    return "Paid reservation: " + reservationId + " remaining balance: " + (balance - total) + "\n";
  }

  // run the query and return the remaining balance
  private void payItinerary(int reserveId, int balance, int total) {
    String updateUser = "UPDATE UserInfo SET balance = " + (balance - total) + " WHERE username = '" + username + "'";
    try {
      Statement payStatement = conn.createStatement();
      payStatement.executeUpdate(updateUser);
      updateReserveStatement.clearParameters();
      updateReserveStatement.setInt(1, 1);
      updateReserveStatement.setInt(2, reserveId);
      updateReserveStatement.executeUpdate();
    } catch (SQLException e) {}
  }

  // return true if the reservation not found
  // also return ture if the reservation is paid
  private boolean findReservation(int reservationId) {
    try {
      findStatement.clearParameters();
      findStatement.setString(1, username);
      findStatement.setInt(2, reservationId);
      ResultSet findResult = findStatement.executeQuery();
      findResult.next();
      findResult.getInt("reserve_id");
      findResult.close();
    } catch (SQLException e) {
      return true;
    }
    return false;
  }

  private int getSum(int reservationId) {
    int sum = 0;
    String getPrice = "SELECT price FROM Flights WHERE fid = ";
    try {
      getFidsStatement.clearParameters();
      getFidsStatement.setInt(1, reservationId);
      Statement getSumStatement = conn.createStatement();
      ResultSet fids = getFidsStatement.executeQuery();
      fids.next();
      int fid1 = fids.getInt("fid1");
      int fid2 = fids.getInt("fid2");
      fids.close();
      ResultSet fidPrice = getSumStatement.executeQuery(getPrice + fid1);
      fidPrice.next();
      sum += fidPrice.getInt("price");
      fidPrice.close();
      if (fid2 != 0) {
        fidPrice = getSumStatement.executeQuery(getPrice + fid2);
        fidPrice.next();
        sum += fidPrice.getInt("price");
        fidPrice.close();
      }
    } catch (SQLException e) {}
    return sum;
  }


  /**
   * Implements the cancel operation.
   *
   * @param reservationId the reservation ID to cancel
   *
   * @return If no user has logged in, then return "Cannot cancel reservations, not logged in\n"
   * For all other errors, return "Failed to cancel reservation [reservationId]"
   *
   * If successful, return "Canceled reservation [reservationId]"
   *
   * Even though a reservation has been canceled, its ID should not be reused by the system.
   */
  public String transaction_cancel(int reservationId) {
    if (username == null) {
      return "Cannot cancel reservations, not logged in\n";
    }
    try {
      beginTransaction();
      verifyReserveStatement.clearParameters();
      verifyReserveStatement.setInt(1, reservationId);
      ResultSet result = verifyReserveStatement.executeQuery();
      result.next();
      String identity = result.getString("username");
      int paid = result.getInt("paid");
      result.close();
      int sum = 0;
      if (identity.equalsIgnoreCase(username)) {
        if (paid == 1) {
          updateBalance(reservationId);
        }
        cancelReserve(reservationId);
        commitTransaction();
      } else {
        rollbackTransaction();
        return "Failed to cancel reservation " + reservationId + "\n";
      }
    } catch (SQLException e) {
      return "Failed to cancel reservation " + reservationId + "\n";
    }
    return "Canceled reservation " + reservationId + "\n";
  }

  private void cancelReserve(int reserveId) {
    try {
      cancelReserveStatement.clearParameters();
      cancelReserveStatement.setInt(1, reserveId);
      cancelReserveStatement.executeUpdate();
    } catch (SQLException e) {}
  }

  private void updateBalance(int reserveId) {
    try {
      int sum = 0;
      sum = getSum(reserveId);
      updateBalanceStatement.clearParameters();
      updateBalanceStatement.setString(1, username);
      updateBalanceStatement.setInt(2, sum);
      updateBalanceStatement.setString(3, username);
      updateBalanceStatement.executeUpdate();
    } catch (SQLException e) {}
  }

  /* some utility functions below */

  public void beginTransaction() throws SQLException {
    conn.setAutoCommit(false);
    beginTransactionStatement.executeUpdate();
  }

  public void commitTransaction() throws SQLException {
    commitTransactionStatement.executeUpdate();
    conn.setAutoCommit(true);
  }

  public void rollbackTransaction() throws SQLException {
    rollbackTransactionStatement.executeUpdate();
    conn.setAutoCommit(true);
  }
}
