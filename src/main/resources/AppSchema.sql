DROP TABLE IF EXISTS UserRoles;

DROP TABLE IF EXISTS RolePermissions;

DROP TABLE IF EXISTS Permissions;

DROP TABLE IF EXISTS Roles;

DROP TABLE IF EXISTS Users;

CREATE TABLE Users (
    -- Primary Key: 64-bit integer, starts at 0, increments by 1
                       userId                 BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY (START WITH 0 MINVALUE 0),

    -- Unique Login
                       loginName              TEXT NOT NULL UNIQUE,

    -- User Details
                       firstName              TEXT NOT NULL,
                       lastName               TEXT NOT NULL,
                       email                  TEXT NOT NULL UNIQUE,
                       phone                  TEXT NOT NULL,

    -- Audit & Security
                       userCreationTime       TIMESTAMPTZ NOT NULL,
                       hashedPassword         TEXT NOT NULL,
                       mustResetPassword      BOOLEAN NOT NULL,
                       userPasswordUpdateTime TIMESTAMPTZ NOT NULL,
                       enabled                BOOLEAN NOT NULL
);

WITH environment AS (
    SELECT now() AS fixedTime
),
     user_data(loginName, firstName, lastName, email, phone, hashedPassword, mustResetPassword, enabled) AS (
         VALUES
             (
                 'neo',
                 'Neophytos',
                 'Michael',
                 'nmichael@gmail.com',
                 '+35796776506',
                 '$argon2id$v=19$m=65536,t=3,p=1$fqA1318nmp1vvTixJN4mVw$W1LDm3rYfzfaCvZO6T+1EieHOlc/EMdcMVFRWjqV6js',
                 false,
                 true
             ),
             (
                 'brent',
                 'Brent',
                 'Walker',
                 'brenthwalker@gmail.com',
                 '+35796776506',
                 '$argon2id$v=19$m=65536,t=3,p=1$fqA1318nmp1vvTixJN4mVw$W1LDm3rYfzfaCvZO6T+1EieHOlc/EMdcMVFRWjqV6js',
                 false,
                 true
             )
     )
INSERT INTO users (
    loginName,
    firstName,
    lastName,
    email,
    phone,
    userCreationTime,
    hashedPassword,
    mustResetPassword,
    userPasswordUpdateTime,
    enabled
)
SELECT
    d.loginName,
    d.firstName,
    d.lastName,
    d.email,
    d.phone,
    e.fixedTime,
    d.hashedPassword,
    d.mustResetPassword,
    e.fixedTime,
    d.enabled
FROM user_data d
CROSS JOIN environment e;

create table Roles
(
    roleId BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY (START WITH 0 MINVALUE 0),
    roleName TEXT NOT NULL UNIQUE,
    createdBy BIGINT NOT NULL REFERENCES Users(userId),
    creationTime TIMESTAMPTZ NOT NULL
);

insert into Roles (roleName, createdBy, creationTime) values('Admin', 0, now());

create table Permissions
(
    permissionId BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY (START WITH 0 MINVALUE 0),
    permissionName varchar(128) unique not null
);

INSERT INTO Permissions (permissionName)
VALUES
    ('CanSeeAdminApp'),
    ('CanUseRiskApp'),
    ('CanUseBoApp'),
    ('CanCreateBoUsers'),
    ('CanSeeBoUsers'),
    ('CanCreateBoRoles'),
    ('CanDeleteBoRoles'),
    ('CanSeeAllLiveSessions'),
    ('CanRenewJwtToken'),
    ('CanSeeAllBoPermissions'),
    ('CanSeeAllBoRoles');

create table RolePermissions
(
  roleId bigint not null references Roles(roleId),
  permissionId bigint not null references Permissions(permissionId),

  primary key (roleId, permissionId)
);

create index RolePermissions_PermissionId_Idx on RolePermissions (permissionId);

insert into RolePermissions (roleId, permissionId)
values
    (0, 0),
    (0, 1),
    (0, 2),
    (0, 3),
    (0, 4),
    (0, 5),
    (0, 6),
    (0, 7),
    (0, 8),
    (0, 9),
    (0, 10);

create table UserRoles
(
  userId bigint not null references Users(userId),
  roleId bigint not null references Roles(roleId),

  primary key (userId, roleId)
);

create index UserRoles_RoleId_Idx on UserRoles (roleId);

insert into UserRoles (userId, roleId) values(0, 0);
