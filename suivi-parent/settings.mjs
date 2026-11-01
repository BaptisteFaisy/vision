import { shareIdOf } from './session.mjs';

const params = new URLSearchParams(location.search);
document.getElementById('settings-button').onclick = () => {
  if (params.get('embedded') === '1') location.href = 'isee-caregiver://settings';
  else document.getElementById('settings-dialog').showModal();
};
document.getElementById('close-settings').onclick = () => document.getElementById('settings-dialog').close();
document.getElementById('shared-link-form').onsubmit = event => {
  event.preventDefault();
  const input = document.getElementById('shared-link');
  const id = shareIdOf(input.value.trim());
  input.setCustomValidity(id ? '' : "Paste the full link from your loved one's app.");
  if (input.reportValidity()) location.assign(input.value.trim());
};
document.getElementById('shared-link').oninput = event => event.target.setCustomValidity('');
document.getElementById('shared-link').value = shareIdOf(location.href) ? location.href : '';
