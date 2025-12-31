DROP TABLE IF EXISTS UserRoles;

DROP TABLE IF EXISTS RolePermissions;

DROP TABLE IF EXISTS Permissions;

DROP TABLE IF EXISTS Roles;

DROP TABLE IF EXISTS Users;

CREATE TABLE Users
(
    -- Primary Key: 64-bit integer, starts at 0, increments by 1
    userId                 BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY (START WITH 1 MINVALUE 0),

    -- Unique Login
    loginName              TEXT        NOT NULL UNIQUE,

    -- User Details
    firstName              TEXT        NOT NULL,
    lastName               TEXT        NOT NULL,
    email                  TEXT        NOT NULL UNIQUE,
    phone                  TEXT        NOT NULL,

    -- Audit & Security
    userCreationTime       TIMESTAMPTZ NOT NULL,
    hashedPassword         TEXT        NOT NULL,
    mustResetPassword      BOOLEAN     NOT NULL,
    userPasswordUpdateTime TIMESTAMPTZ NOT NULL,
    enabled                BOOLEAN     NOT NULL,
    creatingUserId         BIGINT      NOT NULL REFERENCES Users (userId)
);

insert into Users (userId, loginName, firstName, lastName, email, phone, userCreationTime, hashedPassword,
                   mustResetPassword, userPasswordUpdateTime, enabled, creatingUserId)
OVERRIDING SYSTEM VALUE
values (0,
        'neo',
        'Neophytos',
        'Michael',
        'nmichael@gmail.com',
        '+35796776506',
        now(),
        '$argon2id$v=19$m=65536,t=3,p=1$fqA1318nmp1vvTixJN4mVw$W1LDm3rYfzfaCvZO6T+1EieHOlc/EMdcMVFRWjqV6js',
        false,
        now(),
        true,
        0);

insert into Users (loginName, firstName, lastName, email, phone, userCreationTime, hashedPassword, mustResetPassword,
                   userPasswordUpdateTime, enabled, creatingUserId)
values ('brent',
        'Brent',
        'Walker',
        'brenthwalker@gmail.com',
        '+35796776506',
        now(),
        '$argon2id$v=19$m=65536,t=3,p=1$fqA1318nmp1vvTixJN4mVw$W1LDm3rYfzfaCvZO6T+1EieHOlc/EMdcMVFRWjqV6js',
        false,
        now(),
        true,
        0);

create table Roles
(
    roleId       BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY (START WITH 0 MINVALUE 0),
    roleName     TEXT        NOT NULL UNIQUE,
    createdBy    BIGINT      NOT NULL REFERENCES Users (userId),
    creationTime TIMESTAMPTZ NOT NULL
);

insert into Roles (roleName, createdBy, creationTime)
values ('Admin', 0, now());

create table Permissions
(
    permissionId   BIGINT PRIMARY KEY,
    permissionName varchar(128) unique not null
);

INSERT INTO Permissions (permissionId, permissionName)
VALUES (0, 'CanCreateUsers'),
       (1, 'CanSeeUsers'),
       (2, 'CanCreateRoles'),
       (3, 'CanDeleteRoles'),
       (4, 'CanSeeAllLiveSessions'),
       (5, 'CanRenewJwtToken'),
       (6, 'CanSeeAllPermissions'),
       (7, 'CanSeeAllRoles'),
       (8, 'CanResetMyPassword'),
       (9, 'CanCheckResetUserPasswordToken');

create table RolePermissions
(
    roleId       bigint not null references Roles (roleId),
    permissionId bigint not null references Permissions (permissionId),

    primary key (roleId, permissionId)
);

create index RolePermissions_PermissionId_Idx on RolePermissions (permissionId);

insert into RolePermissions (roleId, permissionId)
values (0, 0),
       (0, 1),
       (0, 2),
       (0, 3),
       (0, 4),
       (0, 5),
       (0, 6),
       (0, 7),
       (0, 8),
       (0, 9);

create table UserRoles
(
    userId bigint not null references Users (userId),
    roleId bigint not null references Roles (roleId),

    primary key (userId, roleId)
);

create index UserRoles_RoleId_Idx on UserRoles (roleId);

insert into UserRoles (userId, roleId)
values (0, 0);

create table ResetUserPasswordTokens
(
    hashedToken text primary key,
    expirationTime TIMESTAMPTZ not null
);

create index ResetUserPasswordTokens_expirationTime_idx on ResetUserPasswordTokens (expirationTime);
