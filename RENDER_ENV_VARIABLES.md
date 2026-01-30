# Render Environment Variables for MindVex Backend

This document lists all the environment variables that must be configured on [Render](https://dashboard.render.com/) for the MindVex backend to function properly in production.

## Required Environment Variables

Configure these in your Render service dashboard under **Environment > Environment Variables**.

### Database Configuration

| Variable | Description | Example Value |
|----------|-------------|---------------|
| `DATABASE_URL` | PostgreSQL connection URL (Render provides this automatically if using Render PostgreSQL) | `jdbc:postgresql://host:5432/mindvex_db` |

### Email Configuration (for OTP)

| Variable | Value | Description |
|----------|-------|-------------|
| `MAIL_USERNAME` | `vijayendrannavya@gmail.com` | Gmail address for sending OTP emails |
| `MAIL_PASSWORD` | `<App Password>` | **⚠️ Use Gmail App Password, NOT your regular password** |
| `MAIL_HOST` | `smtp.gmail.com` | SMTP server (optional, defaults to Gmail) |
| `MAIL_PORT` | `587` | SMTP port (optional, defaults to 587) |

> **⚠️ IMPORTANT: Gmail App Password Setup**
> 
> 1. Go to [Google Account Security](https://myaccount.google.com/security)
> 2. Enable **2-Step Verification** if not already enabled
> 3. Go to [App Passwords](https://myaccount.google.com/apppasswords)
> 4. Select "Mail" and "Other (Custom name)" → Enter "MindVex"
> 5. Click **Generate** and copy the 16-character password
> 6. Use this App Password for `MAIL_PASSWORD`, NOT your regular Gmail password

### Security Configuration

| Variable | Description | Example Value |
|----------|-------------|---------------|
| `JWT_SECRET` | Strong random secret for JWT token signing (minimum 64 characters) | Generate with: `openssl rand -hex 64` |

### Spring Profile

| Variable | Value | Description |
|----------|-------|-------------|
| `SPRING_PROFILES_ACTIVE` | `prod` | Activates `application-prod.yml` configuration |

## Quick Setup Summary

1. **On Render Dashboard**, navigate to your MindVex Backend service
2. Go to **Environment** → **Environment Variables**
3. Add the following key-value pairs:

```
SPRING_PROFILES_ACTIVE=prod
MAIL_USERNAME=vijayendrannavya@gmail.com
MAIL_PASSWORD=<your-gmail-app-password>
JWT_SECRET=<generate-a-strong-64-char-secret>
```

4. Click **Save Changes** and Render will automatically redeploy

## Verifying Email Configuration

After deployment, test the OTP email functionality:
1. Try registering a new user on the frontend
2. Check if the OTP email is received
3. If not, check Render logs for any SMTP errors
