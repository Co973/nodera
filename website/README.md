# Nodera download website

A static, responsive website for Cloudflare Pages. This is separate from the local messaging app in ../public.

## Preview and build

Use Node.js 22.13 or newer (Node 24 recommended).

    npm ci
    npm run dev
    npm run build

The static public output is `dist/client`. No application server, database, Oracle account, or secrets are required to serve this website and its APK.

## Cloudflare Pages: upload the built ZIP

Use `../dist/Nodera-android-site.zip` for a new Pages Direct Upload project. In Cloudflare, open Workers & Pages, create a Pages application, choose Direct Upload / drag and drop, upload the ZIP, and deploy. For an existing Direct Upload project, choose Create a new deployment. Add your domain through the Pages project's Custom domains screen after deployment.

Do not upload the parent project: it contains local app data and private signing tools. Upload only the website ZIP or `dist/client`.

Existing Git-integrated Pages projects do not accept dashboard drag-and-drop deployments. Either deploy `dist/client` with Wrangler to the existing project, or use Git integration with these settings after committing the website folder to GitHub:

- Root directory: `website`
- Framework preset: None
- Build command: `npm run build`
- Output directory: `dist/client`
- NODE_VERSION: `24`

Official instructions: https://developers.cloudflare.com/pages/get-started/direct-upload/

## Updating the download

Replace `public/downloads/Nodera-0.2.0-preview.apk` with the intended signed build, update its filename/version and size in `app/page.tsx`, and regenerate `public/downloads/SHA256SUMS.txt`. Then rebuild and redeploy. Never publish the signing keys.

The existing APK displays the older Mesh name. The site does not claim device validation or audited encryption.

## Validation

Production build and the page's lint checks pass. The generated, unused shadcn components contain pre-existing lint errors; `npm run lint` reports those, while `npx oxlint app vite.config.ts next.config.ts` checks the authored page. No browser interaction or device testing was performed for this website task.


