import type { Metadata } from 'next';
import './globals.css';
export const metadata: Metadata = {
  title: 'Nodera — Local connections. Your people.',
  description:
    'Download the Nodera Android preview. An experimental local-first messaging app for direct connections, without a central messaging server.',
  icons: { icon: '/icon.svg' },
  openGraph: {
    title: 'Nodera — Local connections. Your people.',
    description:
      'An experimental local-first messaging app. Try the Android preview and follow the project on GitHub.',
    type: 'website',
  },
};
export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
