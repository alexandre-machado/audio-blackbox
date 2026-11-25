// Jekyll's special/backup files and GitHub Pages' default exclusions must not
// become public just because static directories are copied instead of rendered.
const defaults = ['Gemfile', 'Gemfile.lock', 'node_modules', 'vendor', 'README.md'];
export function publishable(path, excludes = []) {
  const parts = path.split('/');
  if (parts.some(part => /^[._#]/.test(part) || part.endsWith('~'))) return false;
  return ![...defaults, ...excludes].some(excluded => {
    const pattern = String(excluded).replace(/^\/+|\/+$/g, '');
    const regex = new RegExp('^' + pattern.split('*').map(p => p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('.*') + '(?:/|$)');
    return regex.test(path) || (!pattern.includes('/') && parts.some(p => regex.test(p)));
  });
}
