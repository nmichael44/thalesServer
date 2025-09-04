if OBJECT_ID('neo.dbo.BoUserRoles', 'U') is not null
    drop table neo.dbo.BoUserRoles;

if OBJECT_ID('neo.dbo.BoRolePermissions', 'U') is not null
    drop table neo.dbo.BoRolePermissions;

if OBJECT_ID('neo.dbo.BoPermissions', 'U') is not null
    drop table neo.dbo.BoPermissions;

if OBJECT_ID('neo.dbo.BoRoles', 'U') is not null
    drop table neo.dbo.BoRoles;

if OBJECT_ID('neo.dbo.BoUsers', 'U') is not null
    drop table neo.dbo.BoUsers;

create table neo.dbo.BoUsers
(
    userId bigint identity(0, 1) primary key,
    loginName varchar(64) unique not null,
    firstName varchar(64) not null,
    lastName varchar(64) not null,
    email varchar(128) not null,
    phone varchar(32) not null,
    userCreationTime datetimeoffset not null,
    hashedPassword varchar(256) not null,
    mustResetPassword bit not null,
    userPasswordUpdateTime datetimeoffset not null,
    enabled bit not null
);

INSERT INTO neo.dbo.BoUsers (
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
VALUES (
           'neo',
           'Neophytos',
           'Michael',
           'neophytosm@gmail.com',
           '+35796776506',
           SYSDATETIMEOFFSET(),
           '$argon2id$v=19$m=65536,t=3,p=1$fqA1318nmp1vvTixJN4mVw$W1LDm3rYfzfaCvZO6T+1EieHOlc/EMdcMVFRWjqV6js',
           0,
           SYSDATETIMEOFFSET(),
           1
       );

INSERT INTO neo.dbo.BoUsers (
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
VALUES (
           'kostis',
           'Kostas',
           'Tsitsifioggos',
           'kostis@gmail.com',
           '+35712345678',
           SYSDATETIMEOFFSET(),
           '$argon2id$v=19$m=65536,t=3,p=1$fqA1318nmp1vvTixJN4mVw$W1LDm3rYfzfaCvZO6T+1EieHOlc/EMdcMVFRWjqV6js',
           0,
           SYSDATETIMEOFFSET(),
           1
       );

create table neo.dbo.BoRoles
(
    roleId bigint identity(0, 1) primary key,
    roleName varchar(128) unique not null,
    createdBy bigint not null foreign key references neo.dbo.BoUsers(userId),
    creationTime datetimeoffset not null
);

insert into neo.dbo.BoRoles (roleName, createdBy, creationTime) values('Admin', 0, SYSDATETIMEOFFSET());

create table neo.dbo.BoPermissions
(
    permissionId bigint identity(0, 1) primary key,
    permissionName varchar(128) unique not null
);

INSERT INTO neo.dbo.BoPermissions (permissionName)
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

create table neo.dbo.BoRolePermissions
(
  roleId bigint not null,
  permissionId bigint not null,

  primary key (roleId, permissionId),
    foreign key (roleId) references neo.dbo.BoRoles(roleId),
    foreign key (permissionId) references neo.dbo.BoPermissions(permissionId)
);

create index IX_BoRolePermissions_PermissionId on neo.dbo.BoRolePermissions (permissionId);

insert into neo.dbo.BoRolePermissions (roleId, permissionId)
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

create table neo.dbo.BoUserRoles
(
  userId bigint not null,
  roleId bigint not null,

  primary key (userId, roleId),
  foreign key (userId) references neo.dbo.BoUsers(userId),
  foreign key (roleId) references neo.dbo.BoRoles(roleId)
);

create index IX_BoUserRoles_RoleId on neo.dbo.BoUserRoles (roleId);

insert into neo.dbo.BoUserRoles (userId, roleId) values(0, 0);
