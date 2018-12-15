-- add all your SQL setup statements here.

CREATE TABLE UserInfo (username varchar(20) primary key,
                   password varchar(20),
                   balance int);

CREATE TABLE Itineraries (flight_1 int,
                          flight_2 int,
                          total_time int);

CREATE TABLE Reserve (reserve_id int primary key,
                      username varchar(20),
                      fid1 int,
                      fid2 int,
                      flight_date int,
                      paid int,
                      next_id int);
INSERT INTO Reserve (reserve_id, next_id) VALUES (0, 1);
